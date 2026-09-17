package kr.jm.moalog.core.contracts

import kotlinx.serialization.Serializable

/** RFC 9457 problem details plus stable MoaLog fields. */
@Serializable
data class ApiProblemDto(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
    val code: String? = null,
    val traceId: String? = null,
    val errors: List<ApiFieldErrorDto> = emptyList(),
)

@Serializable
data class ApiFieldErrorDto(
    val field: String,
    val message: String,
)
