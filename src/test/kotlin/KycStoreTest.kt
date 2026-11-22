package com.example

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Tests for the registration rules on the server. Names start with the Test Case IDs
// of the "MSE Invest Test Case Design" document where one exists.
class KycStoreTest {

    companion object {
        private var counter = 0
        private var nextEmail = 1
    }

    // A real user in the database
    private fun newUser(): Int {
        counter += 1
        return UserStore.register("Test User", "kyc$counter@mse.test", "Sample123")!!.id
    }

    private val today = LocalDate.of(2026, 10, 25)

    private fun validDraft(idNumber: String = "12345678") = KycDraftDto(
        step = 4, fullName = "Test User", dateOfBirth = "01/01/1990", address = "Sample Road",
        idType = "National ID", idNumber = idNumber, idPhotoTaken = true, selfieTaken = true,
        payoutMethod = "Mobile money", accountNumber = "0999000000", riskAccepted = true
    )

    // A user whose registration has been submitted (status PENDING)
    private fun submitted(draft: KycDraftDto = validDraft()): Int {
        val user = newUser()
        KycStore.saveDraft(user, draft)
        KycStore.submit(user)
        return user
    }

    @Test
    fun REG_WIZARD_001_completeRegistration_isApprovedByTheReviewer() {
        val user = submitted()
        assertEquals("PENDING", KycStore.status(user))
        assertFalse(KycStore.isApproved(user))

        KycStore.autoReview(user)

        assertEquals("APPROVED", KycStore.status(user))
        assertTrue(KycStore.isApproved(user))
    }

    @Test
    fun REG_WIZARD_005_idNumber0000_isRejected_andCanBeCorrected() {
        val user = submitted(validDraft("0000"))
        KycStore.autoReview(user)

        val rejected = KycStore.get(user)
        assertEquals("REJECTED", rejected.status)
        assertEquals("The ID number could not be verified", rejected.rejectionReason)
        assertEquals(0, rejected.draft.step)

        // Fixing the details moves it back to IN_PROGRESS, and it can be submitted again
        assertEquals("IN_PROGRESS", KycStore.saveDraft(user, validDraft("12345678")).status)
        KycStore.submit(user)
        KycStore.autoReview(user)
        assertEquals("APPROVED", KycStore.status(user))
    }

    @Test
    fun REG_WIZARD_003_idNumberOfThreeCharacters_isRefused() {
        val user = newUser()
        KycStore.saveDraft(user, validDraft("123"))
        val error = assertFailsWith<RuleException> { KycStore.submit(user) }

        assertEquals("Enter your ID number (at least 4 characters)", error.message)
    }

    @Test
    fun REG_WIZARD_004_idNumberOfFourCharacters_isAccepted() {
        val user = submitted(validDraft("1234"))

        assertEquals("PENDING", KycStore.status(user))
    }

    @Test
    fun REG_WIZARD_007_riskNotAccepted_isRefused() {
        val user = newUser()
        KycStore.saveDraft(user, validDraft().copy(riskAccepted = false))
        val error = assertFailsWith<RuleException> { KycStore.submit(user) }

        assertEquals("You must accept the risk warning", error.message)
    }

    @Test
    fun age_exactly18_isAccepted_butOneDayYounger_isRefused() {
        assertNull(validateKyc(validDraft().copy(dateOfBirth = "25/10/2008"), today))
        assertEquals(
            "You must be at least 18 years old",
            validateKyc(validDraft().copy(dateOfBirth = "26/10/2008"), today)
        )
    }

    @Test
    fun dateOfBirth_badFormat_futureAndImpossibleDates_areRefused() {
        assertEquals(
            "Enter your date of birth as DD/MM/YYYY",
            validateKyc(validDraft().copy(dateOfBirth = "1990-01-01"), today)
        )
        assertEquals(
            "Enter your date of birth as DD/MM/YYYY",
            validateKyc(validDraft().copy(dateOfBirth = "31/02/1990"), today)
        )
        assertEquals(
            "The date of birth cannot be in the future",
            validateKyc(validDraft().copy(dateOfBirth = "01/01/2030"), today)
        )
    }

    @Test
    fun accountNumber_mustBeDigitsOnly() {
        assertEquals(
            "Enter a valid account or mobile money number (8 to 20 digits)",
            validateKyc(validDraft().copy(accountNumber = "09990abc00"), today)
        )
    }

    @Test
    fun submit_withoutAnyForm_isRefused() {
        val error = assertFailsWith<RuleException> { KycStore.submit(newUser()) }

        assertEquals("Fill in the registration form first", error.message)
    }

    @Test
    fun underReview_theFormCannotBeChanged() {
        val user = submitted()
        val error = assertFailsWith<RuleException> { KycStore.saveDraft(user, validDraft()) }

        assertEquals("Your registration is under review and cannot be changed", error.message)
    }

    @Test
    fun flaggedIdNumber_waitsForAnAdmin_andCannotBeApprovedTwice() {
        val user = submitted(validDraft("9912345"))
        KycStore.autoReview(user)
        assertEquals("PENDING", KycStore.status(user))        // the reviewer left it for a person

        KycStore.approve(user)
        assertEquals("APPROVED", KycStore.status(user))

        val error = assertFailsWith<RuleException> { KycStore.approve(user) }
        assertEquals("Only a registration that is under review can be approved", error.message)
    }

    @Test
    fun adminReject_needsAReason_andStoresIt() {
        val user = submitted(validDraft("9987654"))

        val error = assertFailsWith<RuleException> { KycStore.reject(user, "ab") }
        assertEquals("Give a reason of 3 to 200 characters", error.message)

        KycStore.reject(user, "Photo of the ID is unclear")
        val view = KycStore.get(user)
        assertEquals("REJECTED", view.status)
        assertEquals("Photo of the ID is unclear", view.rejectionReason)
    }

    @Test
    fun SEC_PRIVACY_numbersAreFullWhileEditing_andMaskedAfterSubmit() {
        val editing = newUser()
        KycStore.saveDraft(editing, validDraft())
        assertEquals("12345678", KycStore.get(editing).draft.idNumber)

        val sent = submitted()
        val view = KycStore.get(sent).draft
        assertEquals("••••••78", view.idNumber)
        assertEquals("••••••••00", view.accountNumber)
    }

    @Test
    fun usersNeverShareRegistrationStatus() {
        val approved = submitted()
        KycStore.autoReview(approved)
        val other = newUser()

        assertTrue(KycStore.isApproved(approved))
        assertFalse(KycStore.isApproved(other))
        assertEquals("NOT_STARTED", KycStore.status(other))
    }

    @Test
    fun pendingReviews_listsFlaggedRegistrations_withMaskedIdNumbers() {
        val investor = UserStore.register("Review Person", "review${nextEmail++}@mse.test", "Sample123")!!
        KycStore.saveDraft(investor.id, validDraft("9912345"))
        KycStore.submit(investor.id)

        val item = KycStore.pendingReviews().first { it.userId == investor.id }
        assertEquals("Review Person", item.fullName)
        assertEquals("•••••45", item.idNumber)
    }
}

