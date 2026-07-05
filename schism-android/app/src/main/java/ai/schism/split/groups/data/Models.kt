package ai.schism.split.groups.data

/** Domain models exposed to the UI layer (no DTO/entity leakage upward). */

data class Participant(
    val id: String,
    val groupId: String,
    val name: String,
)

data class Group(
    val id: String,
    val name: String,
    val information: String,
    val currency: String,
    val currencyCode: String,
    val participants: List<Participant>,
    val isFavorite: Boolean = false,
    val activeParticipantId: String? = null,
)
