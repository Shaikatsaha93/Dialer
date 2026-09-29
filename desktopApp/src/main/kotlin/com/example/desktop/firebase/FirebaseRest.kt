package com.example.desktop.firebase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * Firebase Auth and Firestore through their REST APIs (the Firebase SDKs are Android-only).
 * Same project, users and security rules as the Android app, so approvals work across both.
 */
class FirebaseRest(
    private val apiKey: String,
    private val projectId: String
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** A signed-in Firebase user. [idToken] is valid until [expiresAtMillis]. */
    data class Session(
        val uid: String,
        val idToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val email: String?
    )

    class FirebaseException(val status: Int, val code: String, message: String) : Exception(message)

    // ---- Auth ----

    suspend fun signInAnonymously(): Session =
        authCall("accounts:signUp", buildJsonObject { put("returnSecureToken", true) }, email = null)

    suspend fun signInWithPassword(email: String, password: String): Session =
        authCall(
            "accounts:signInWithPassword",
            buildJsonObject {
                put("email", email)
                put("password", password)
                put("returnSecureToken", true)
            },
            email = email
        )

    /** Gets a fresh ID token for a saved session. */
    suspend fun refresh(refreshToken: String, email: String?): Session {
        val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8")
        val reply = send(
            HttpRequest.newBuilder(URI("https://securetoken.googleapis.com/v1/token?key=$apiKey"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
        )
        return Session(
            uid = reply.str("user_id"),
            idToken = reply.str("id_token"),
            refreshToken = reply.str("refresh_token"),
            expiresAtMillis = System.currentTimeMillis() + reply.str("expires_in").toLong() * 1000,
            email = email
        )
    }

    private suspend fun authCall(method: String, body: JsonObject, email: String?): Session {
        val reply = send(
            HttpRequest.newBuilder(URI("https://identitytoolkit.googleapis.com/v1/$method?key=$apiKey"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        )
        return Session(
            uid = reply.str("localId"),
            idToken = reply.str("idToken"),
            refreshToken = reply.str("refreshToken"),
            expiresAtMillis = System.currentTimeMillis() + reply.str("expiresIn").toLong() * 1000,
            email = email
        )
    }

    // ---- Firestore ----

    private val documentsRoot = "projects/$projectId/databases/(default)/documents"
    private val baseUrl = "https://firestore.googleapis.com/v1/$documentsRoot"

    /** The document's fields, or null when it does not exist. Throws on permission errors. */
    suspend fun getDocument(token: String, path: String): Map<String, Any?>? = try {
        val reply = send(authorized(token, "$baseUrl/$path").GET())
        decodeFields(reply["fields"]?.jsonObject)
    } catch (e: FirebaseException) {
        if (e.status == 404) null else throw e
    }

    /** All documents of a collection as (id, fields). */
    suspend fun listDocuments(token: String, collection: String): List<Pair<String, Map<String, Any?>>> {
        val result = mutableListOf<Pair<String, Map<String, Any?>>>()
        var pageToken: String? = null
        do {
            val url = "$baseUrl/$collection?pageSize=300" + (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val reply = send(authorized(token, url).GET())
            reply["documents"]?.jsonArray?.forEach { doc ->
                val obj = doc.jsonObject
                result += obj.str("name").substringAfterLast('/') to (decodeFields(obj["fields"]?.jsonObject) ?: emptyMap())
            }
            pageToken = (reply["nextPageToken"] as? JsonPrimitive)?.contentOrNull
        } while (pageToken != null)
        return result
    }

    /**
     * Writes [fields] to a document in one commit.
     * [mask] = the fields to change (a field in the mask but not in [fields] is deleted); null = replace all.
     * [serverTimeFields] are set to the server's time. [mustExist] adds a precondition.
     */
    suspend fun write(
        token: String,
        path: String,
        fields: Map<String, Any?>,
        mask: List<String>? = null,
        serverTimeFields: List<String> = emptyList(),
        mustExist: Boolean? = null
    ) {
        val write = buildJsonObject {
            put("update", buildJsonObject {
                put("name", "$documentsRoot/$path")
                put("fields", encodeFields(fields))
            })
            if (mask != null) put("updateMask", buildJsonObject { put("fieldPaths", JsonArray(mask.map { JsonPrimitive(it) })) })
            if (serverTimeFields.isNotEmpty()) {
                put("updateTransforms", buildJsonArray {
                    serverTimeFields.forEach { field ->
                        add(buildJsonObject {
                            put("fieldPath", field)
                            put("setToServerValue", "REQUEST_TIME")
                        })
                    }
                })
            }
            if (mustExist != null) put("currentDocument", buildJsonObject { put("exists", mustExist) })
        }
        val body = buildJsonObject { put("writes", JsonArray(listOf(write))) }
        send(
            authorized(token, "https://firestore.googleapis.com/v1/$documentsRoot:commit")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        )
    }

    suspend fun deleteDocument(token: String, path: String) {
        send(authorized(token, "$baseUrl/$path").DELETE())
    }

    // ---- Plumbing ----

    private fun authorized(token: String, url: String) =
        HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $token").timeout(Duration.ofSeconds(20))

    private suspend fun send(request: HttpRequest.Builder): JsonObject = withContext(Dispatchers.IO) {
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        val body = response.body().orEmpty()
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
        if (response.statusCode() !in 200..299) {
            val error = (parsed as? JsonObject)?.get("error")
            val obj = error as? JsonObject
            val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: (error as? JsonPrimitive)?.contentOrNull ?: body.take(200)
            val code = obj?.get("status")?.jsonPrimitive?.contentOrNull ?: message
            throw FirebaseException(response.statusCode(), code, message)
        }
        (parsed as? JsonObject) ?: JsonObject(emptyMap())
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: throw FirebaseException(0, "BAD_REPLY", "Missing $key in Firebase reply")

    /** Firestore typed values -> Kotlin: String, Boolean, Long, Double, Instant (timestamps), null. */
    private fun decodeFields(fields: JsonObject?): Map<String, Any?>? {
        fields ?: return emptyMap()
        return fields.mapValues { (_, value) -> decodeValue(value.jsonObject) }
    }

    private fun decodeValue(value: JsonObject): Any? {
        val (type, raw) = value.entries.firstOrNull() ?: return null
        return when (type) {
            "stringValue" -> raw.jsonPrimitive.content
            "booleanValue" -> raw.jsonPrimitive.booleanOrNull
            "integerValue" -> raw.jsonPrimitive.content.toLongOrNull()
            "doubleValue" -> raw.jsonPrimitive.content.toDoubleOrNull()
            "timestampValue" -> runCatching { Instant.parse(raw.jsonPrimitive.content) }.getOrNull()
            else -> null
        }
    }

    private fun encodeFields(fields: Map<String, Any?>): JsonObject = buildJsonObject {
        fields.forEach { (key, value) -> put(key, encodeValue(value)) }
    }

    private fun encodeValue(value: Any?): JsonElement = buildJsonObject {
        when (value) {
            null -> put("nullValue", JsonNull)
            is String -> put("stringValue", value)
            is Boolean -> put("booleanValue", value)
            is Int, is Long -> put("integerValue", value.toString())
            is Double -> put("doubleValue", value)
            is Instant -> put("timestampValue", value.toString())
            else -> put("stringValue", value.toString())
        }
    }
}
