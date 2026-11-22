package com.example

import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

private val ID_TYPES = setOf("National ID", "Passport", "Licence")
private val PAYOUT_METHODS = setOf("Mobile money", "Bank")
private val ACCOUNT_NUMBER = Regex("\\d{8,20}")

// STRICT means 31/02/1990 is refused, not quietly turned into a different date
private val DOB_FORMAT = DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.US).withResolverStyle(ResolverStyle.STRICT)

// 12345678 -> ••••••78
private fun mask(text: String): String =
    if (text.length <= 2) text else "•".repeat(text.length - 2) + text.takeLast(2)

// ---------- Checks. Each returns an error message, or null when everything is fine ----------

// Light checks for a form that is still being filled in. They mostly protect the server from huge or odd data.
fun validateDraftShape(d: KycDraftDto): String? = when {
    d.step !in 0..4 -> "Invalid step"
    d.fullName.length > 80 || d.address.length > 200 -> "Text is too long"
    d.dateOfBirth.length > 10 || d.idNumber.length > 30 || d.accountNumber.length > 30 -> "Text is too long"
    d.idType !in ID_TYPES -> "Choose an ID type"
    d.payoutMethod !in PAYOUT_METHODS -> "Choose how you want to pay and receive money"
    else -> null
}

// Everything must be right before the registration can be submitted
fun validateKyc(d: KycDraftDto, today: LocalDate = LocalDate.now()): String? {
    if (d.fullName.trim().length !in 2..80) return "Enter your full name"

    val dob = try {
        LocalDate.parse(d.dateOfBirth.trim(), DOB_FORMAT)
    } catch (e: DateTimeParseException) {
        return "Enter your date of birth as DD/MM/YYYY"
    }
    if (dob.isAfter(today)) return "The date of birth cannot be in the future"
    if (dob.plusYears(18).isAfter(today)) return "You must be at least 18 years old"

    if (d.address.trim().length !in 3..200) return "Enter your home address"
    if (d.idType !in ID_TYPES) return "Choose an ID type"
    if (d.idNumber.trim().length !in 4..30) return "Enter your ID number (at least 4 characters)"
    if (!d.idPhotoTaken) return "Add a photo of your ID"
    if (!d.selfieTaken) return "Add a selfie"
    if (d.payoutMethod !in PAYOUT_METHODS) return "Choose how you want to pay and receive money"
    if (!ACCOUNT_NUMBER.matches(d.accountNumber.trim())) return "Enter a valid account or mobile money number (8 to 20 digits)"
    if (!d.riskAccepted) return "You must accept the risk warning"
    return null
}

// ---------- What is saved for ONE user ----------

private val storedJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal class KycRecord(
    var status: String,
    var draft: KycDraftDto,
    var rejectionReason: String?,
    var submittedAt: String?
)

object KycStore {

    // ---------- Reading and writing one record ----------

    // A user with no row yet has simply not started
    private fun load(c: Connection, userId: Int): KycRecord =
        c.query(
            "SELECT status, rejection_reason, submitted_at, draft_json FROM kyc WHERE user_id = ?", userId
        ) {
            KycRecord(
                status = it.getString(1),
                draft = storedJson.decodeFromString(KycDraftDto.serializer(), it.getString(4)),
                rejectionReason = it.getString(2),
                submittedAt = it.getString(3)
            )
        }.firstOrNull() ?: KycRecord("NOT_STARTED", KycDraftDto(), null, null)

    // "Upsert": insert the row, or update it if this user already has one
    private fun save(c: Connection, userId: Int, r: KycRecord) {
        c.execute(
            "INSERT INTO kyc (user_id, status, rejection_reason, submitted_at, draft_json) VALUES (?, ?, ?, ?, ?) " +
                    "ON CONFLICT(user_id) DO UPDATE SET status = excluded.status, " +
                    "rejection_reason = excluded.rejection_reason, submitted_at = excluded.submitted_at, " +
                    "draft_json = excluded.draft_json",
            userId, r.status, r.rejectionReason, r.submittedAt,
            storedJson.encodeToString(KycDraftDto.serializer(), r.draft)
        )
    }

    // While the form is still editable the user gets their own text back.
    // Once it is submitted, sensitive numbers come back masked (data minimisation).
    private fun view(r: KycRecord): KycDto {
        val editable = r.status == "NOT_STARTED" || r.status == "IN_PROGRESS" || r.status == "REJECTED"
        val shown = if (editable) r.draft else r.draft.copy(
            idNumber = mask(r.draft.idNumber),
            accountNumber = mask(r.draft.accountNumber)
        )
        return KycDto(r.status, r.rejectionReason, r.submittedAt, shown)
    }

    // ---------- Reading ----------

    fun status(userId: Int): String = Db.transaction { c ->

        c.query("SELECT status FROM kyc WHERE user_id = ?", userId) { it.getString(1) }.firstOrNull() ?: "NOT_STARTED"
    }

    // The ONE question every order asks
    fun isApproved(userId: Int): Boolean = status(userId) == "APPROVED"

    fun get(userId: Int): KycDto = Db.transaction { c -> view(load(c, userId)) }

    // The people whose registration is still waiting. After a restart, the reviewer looks at them again.
    fun pendingUserIds(): List<Int> = Db.transaction { c ->
        c.query("SELECT user_id FROM kyc WHERE status = 'PENDING'") { it.getInt(1) }
    }

    // ---------- The user's own steps ----------

    fun saveDraft(userId: Int, draft: KycDraftDto): KycDto {
        validateDraftShape(draft)?.let { throw RuleException(it) }

        return Db.transaction { c ->
            val r = load(c, userId)
            when (r.status) {
                "PENDING" -> throw RuleException("Your registration is under review and cannot be changed")
                "APPROVED" -> throw RuleException("Your registration is already approved")
            }
            r.draft = draft
            r.rejectionReason = null
            if (r.status == "NOT_STARTED" || r.status == "REJECTED") r.status = "IN_PROGRESS"
            save(c, userId, r)
            view(r)
        }
    }

    fun submit(userId: Int): KycDto = Db.transaction { c ->
        val r = load(c, userId)
        when (r.status) {
            "PENDING" -> throw RuleException("Your registration is already under review")
            "APPROVED" -> throw RuleException("Your registration is already approved")
            "NOT_STARTED" -> throw RuleException("Fill in the registration form first")
        }
        validateKyc(r.draft)?.let { throw RuleException(it) }

        r.status = "PENDING"
        r.rejectionReason = null
        r.submittedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.US))
        save(c, userId, r)
        view(r)
    }

    // ---------- The simulated reviewer (see Reviewer.kt) ----------

    fun autoReview(userId: Int) {
        Db.transaction { c ->
            val r = load(c, userId)
            if (r.status != "PENDING") return@transaction          // already decided, or changed meanwhile

            val id = r.draft.idNumber.trim()
            when {
                id == "0000" -> markRejected(r, "The ID number could not be verified")
                id.startsWith("99") -> return@transaction           // flagged: waits for a human reviewer
                else -> markApproved(r)
            }
            save(c, userId, r)
        }
    }

    // ---------- A human reviewer (admin) ----------

    fun pendingReviews(): List<KycReviewItem> = Db.transaction { c ->
        c.query(
            "SELECT k.user_id, u.full_name, u.identifier, k.submitted_at, k.draft_json " +
                    "FROM kyc k JOIN users u ON u.id = k.user_id " +
                    "WHERE k.status = 'PENDING' ORDER BY k.user_id"
        ) {
            val draft = storedJson.decodeFromString(KycDraftDto.serializer(), it.getString(5))
            KycReviewItem(
                userId = it.getInt(1),
                fullName = it.getString(2),
                identifier = it.getString(3),
                submittedAt = it.getString(4),
                dateOfBirth = draft.dateOfBirth,
                idType = draft.idType,
                idNumber = mask(draft.idNumber)          // a reviewer sees what they need, not more
            )
        }
    }

    private fun hasRegistration(c: Connection, userId: Int): Boolean =
        c.query("SELECT 1 FROM kyc WHERE user_id = ?", userId) { 1 }.isNotEmpty()

    fun approve(userId: Int) {
        Db.transaction { c ->
            if (!hasRegistration(c, userId)) throw RuleException("No registration found for this user")
            val r = load(c, userId)
            if (r.status != "PENDING") throw RuleException("Only a registration that is under review can be approved")
            markApproved(r)
            save(c, userId, r)
        }
    }

    fun reject(userId: Int, reason: String) {
        val cleaned = reason.trim()
        if (cleaned.length !in 3..200) throw RuleException("Give a reason of 3 to 200 characters")

        Db.transaction { c ->
            if (!hasRegistration(c, userId)) throw RuleException("No registration found for this user")
            val r = load(c, userId)
            if (r.status != "PENDING") throw RuleException("Only a registration that is under review can be rejected")
            markRejected(r, cleaned)
            save(c, userId, r)
        }
    }

    // ---------- The only two places that set a final status ----------

    private fun markApproved(r: KycRecord) {
        r.status = "APPROVED"
        r.rejectionReason = null
    }

    private fun markRejected(r: KycRecord, reason: String) {
        r.status = "REJECTED"
        r.rejectionReason = reason
        r.draft = r.draft.copy(step = 0, riskAccepted = false)     // back to step 1, details kept
    }
}