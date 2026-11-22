package com.example


import kotlinx.serialization.Serializable

// The registration form, as the phone fills it in. It is used in both directions.
@Serializable
data class KycDraftDto(
    val step: Int = 0,
    val fullName: String = "",
    val dateOfBirth: String = "",        // DD/MM/YYYY
    val address: String = "",
    val idType: String = "National ID",
    val idNumber: String = "",
    val idPhotoTaken: Boolean = false,
    val selfieTaken: Boolean = false,
    val payoutMethod: String = "Mobile money",
    val accountNumber: String = "",
    val riskAccepted: Boolean = false
)

@Serializable
data class KycDto(
    val status: String,                  // NOT_STARTED, IN_PROGRESS, PENDING, REJECTED or APPROVED
    val rejectionReason: String? = null,
    val submittedAt: String? = null,
    val draft: KycDraftDto = KycDraftDto()
)

// What a reviewer sees in the waiting list. The ID number is masked on purpose.
@Serializable
data class KycReviewItem(
    val userId: Int,
    val fullName: String,
    val identifier: String,
    val submittedAt: String?,
    val dateOfBirth: String,
    val idType: String,
    val idNumber: String
)

@Serializable
data class RejectRequest(val reason: String)

@Serializable
data class KycDecisionResponse(val userId: Int, val status: String)