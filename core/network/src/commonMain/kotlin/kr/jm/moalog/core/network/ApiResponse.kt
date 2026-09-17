package kr.jm.moalog.core.network

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kr.jm.moalog.core.contracts.ApiProblemDto

class MoaLogApiException(
    val status: Int,
    val problem: ApiProblemDto?,
) : Exception(problem?.detail ?: problem?.title ?: "HTTP request failed with status $status")

suspend inline fun <reified T> HttpResponse.bodyOrThrow(): T {
    throwIfError()
    return body()
}

suspend fun HttpResponse.throwIfError() {
    if (status.isSuccess()) return

    val responseText = bodyAsText()
    val problem = runCatching {
        MoaLogJson.decodeFromString<ApiProblemDto>(responseText)
    }.getOrNull()
    throw MoaLogApiException(status = status.value, problem = problem)
}
