package com.nganyaflow.app.core.network

import com.nganyaflow.app.core.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit

val WireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
@Serializable data class ReportBatch(val records: List<BoardingReport>)
@Serializable data class ObservationBatch(val records: List<Observation>)
@Serializable data class Rejection(val id: String, val reason: String)
@Serializable data class BatchResult(
    @kotlinx.serialization.SerialName("accepted_ids") val acceptedIds: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("duplicate_ids") val duplicateIds: List<String> = emptyList(),
    val rejected: List<Rejection> = emptyList(),
)
@Serializable data class RedeemCode(val code: String)
@Serializable data class CloseSession(@kotlinx.serialization.SerialName("ended_at") val endedAt: String)
@Serializable data class Health(val status: String, @kotlinx.serialization.SerialName("model_loaded") val modelLoaded: Boolean = false)

interface NganyaApi {
    @GET("health") suspend fun health(): Health
    @GET("api/v1/meta") suspend fun meta(): Meta
    @GET("api/v1/stages") suspend fun stages(): List<Stage>
    @GET("api/v1/routes") suspend fun routes(@Query("stage_id") stageId: String? = null): List<Route>
    @GET("api/v1/stages/{stage}/estimates") suspend fun stageEstimates(@Path("stage") stage: String, @Query("direction") direction: String? = null): StageEstimates
    @GET("api/v1/estimates") suspend fun estimate(@Query("stage_id") stage: String, @Query("route_id") route: String, @Query("direction") direction: String): Estimate
    @POST("api/v1/reports") suspend fun reports(@Body body: ReportBatch): BatchResult
    @DELETE("api/v1/reports/{id}") suspend fun retract(@Path("id") id: String, @Query("install_id") installId: String)
    @POST("api/v1/deletion-requests") suspend fun delete(@Body body: DeletionRequest): DeletionStatus
    @GET("api/v1/deletion-requests/{id}") suspend fun deletion(@Path("id") id: String): DeletionStatus
    @POST("api/v1/auth/redeem") suspend fun redeem(@Body body: RedeemCode): AuthSession
    @GET("api/v1/auth/me") suspend fun me(): JsonObject
    @POST("api/v1/sessions") suspend fun session(@Body body: Session): JsonObject
    @POST("api/v1/observations/sync") suspend fun observations(@Body body: ObservationBatch): BatchResult
    @PUT("api/v1/sessions/{id}/end") suspend fun end(@Path("id") id: String, @Body body: CloseSession): JsonObject
    @POST("api/v1/observations/{id}/void") suspend fun void(@Path("id") id: String): JsonObject
    @GET("api/v1/demand") suspend fun demand(@Query("route_id") route: String, @Query("direction") direction: String, @Query("window_minutes") window: Int): Demand
}

class AuthInterceptor(private val token: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val isProtected = path == "/api/v1/demand" || path == "/api/v1/auth/me" || path.startsWith("/api/v1/sessions") || path.startsWith("/api/v1/observations")
        val auth = if (isProtected) token() else null
        return chain.proceed(if (auth == null) request else request.newBuilder().header("Authorization", "Bearer $auth").build())
    }
}
fun createApi(baseUrl: String, token: () -> String? = { null }, client: OkHttpClient? = null): NganyaApi {
    val http = client ?: OkHttpClient.Builder().addInterceptor(AuthInterceptor(token))
        .callTimeout(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
    return Retrofit.Builder().baseUrl(baseUrl).client(http)
        .addConverterFactory(WireJson.asConverterFactory("application/json".toMediaType())).build().create(NganyaApi::class.java)
}

sealed class ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>()
    data class Failure(val error: ApiFailure) : ApiResult<Nothing>()
}
class ApiFailure(val code: Int? = null, override val message: String, val retryable: Boolean = false) : Exception(message)
suspend fun <T> apiResult(block: suspend () -> T): ApiResult<T> = try { ApiResult.Success(block()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
    ApiResult.Failure(when (e) {
        is ApiFailure -> e
        is HttpException -> {
            val detail = runCatching { WireJson.parseToJsonElement(e.response()?.errorBody()?.string().orEmpty()).let { (it as? JsonObject)?.get("detail")?.jsonPrimitive?.content } }.getOrNull()
            ApiFailure(e.code(), detail ?: when(e.code()) { 401 -> "Sign in again"; 403 -> "This access code has the wrong role"; 409 -> "This record needs correction"; 422 -> "Check the record details and time"; 429 -> "Too many attempts, wait a few minutes"; else -> "Server returned ${e.code()}" }, e.code() == 429 || e.code() >= 500)
        }
        is SerializationException, is IllegalArgumentException -> ApiFailure(message = "The server sent an unreadable response")
        is IOException -> ApiFailure(message = "Cannot reach the server. Saved work will retry.", retryable = true)
        else -> ApiFailure(message = e.message ?: "The request could not be completed")
    })
}
suspend fun <T> apiCall(block: suspend () -> T): T = when(val result = apiResult(block)) { is ApiResult.Success -> result.value; is ApiResult.Failure -> throw result.error }
