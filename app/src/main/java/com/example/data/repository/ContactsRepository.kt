package com.example.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.model.PhoneContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class ContactsRepository(private val context: Context) : ContactsSource {

    private val _contacts = MutableStateFlow<List<PhoneContact>>(emptyList())
    override val contacts: StateFlow<List<PhoneContact>> = _contacts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val numberToNameMap = mutableMapOf<String, String>()

    override fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun loadContacts(): Unit = withContext<Unit>(Dispatchers.IO) {
        if (!hasContactsPermission()) {
            Log.d(TAG, "READ_CONTACTS permission not granted yet")
            return@withContext
        }

        _isLoading.value = true
        val contactsMap = mutableMapOf<String, MutableList<String>>()
        val contactNames = mutableMapOf<String, String>()
        val contactPhotos = mutableMapOf<String, String?>()

        try {
            val contentResolver = context.contentResolver
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            )

            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (it.moveToNext()) {
                    val id = if (idIndex != -1) it.getString(idIndex) else ""
                    val name = if (nameIndex != -1) it.getString(nameIndex) ?: "" else ""
                    val rawNumber = if (numberIndex != -1) it.getString(numberIndex) ?: "" else ""
                    val photoUri = if (photoIndex != -1) it.getString(photoIndex) else null

                    if (id.isNotBlank() && rawNumber.isNotBlank()) {
                        val cleanNum = rawNumber.replace(Regex("[\\s\\-\\(\\)]"), "")
                        val numberList = contactsMap.getOrPut(id) { mutableListOf() }
                        if (!numberList.contains(cleanNum)) {
                            numberList.add(cleanNum)
                        }
                        if (name.isNotBlank()) {
                            contactNames[id] = name
                        }
                        if (photoUri != null) {
                            contactPhotos[id] = photoUri
                        }
                    }
                }
            }

            val list = contactsMap.mapNotNull { (id, numbers) ->
                val name = contactNames[id] ?: return@mapNotNull null
                val primaryNum = numbers.firstOrNull() ?: ""
                if (primaryNum.isBlank()) return@mapNotNull null

                PhoneContact(
                    id = id,
                    name = name,
                    primaryNumber = primaryNum,
                    allNumbers = numbers,
                    photoUri = contactPhotos[id]
                )
            }.sortedBy { it.name.lowercase() }

            // Build lookup map for fast reverse number matching
            val newMap = mutableMapOf<String, String>()
            list.forEach { contact ->
                contact.allNumbers.forEach { num ->
                    val normalized = normalizeNumber(num)
                    if (normalized.isNotBlank()) {
                        newMap[normalized] = contact.name
                    }
                }
            }
            synchronized(numberToNameMap) {
                numberToNameMap.clear()
                numberToNameMap.putAll(newMap)
            }

            _contacts.value = list
            Log.d(TAG, "Loaded ${list.size} contacts from device")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading contacts: ${e.message}", e)
        } finally {
            _isLoading.value = false
        }
    }

    override fun findContactName(rawNumberOrUri: String): String? {
        val clean = normalizeNumber(rawNumberOrUri)
        if (clean.isBlank()) return null

        synchronized(numberToNameMap) {
            // Direct match
            numberToNameMap[clean]?.let { return it }

            // Suffix matching (for local vs international formats, e.g. +88017... vs 017...)
            for ((num, name) in numberToNameMap) {
                if (clean.length >= 6 && num.length >= 6) {
                    if (clean.endsWith(num) || num.endsWith(clean)) {
                        return name
                    }
                }
            }
        }
        return null
    }

    private fun normalizeNumber(raw: String): String {
        return raw.removePrefix("sip:")
            .removePrefix("sips:")
            .substringBefore("@")
            .replace(Regex("[\\s\\-\\(\\)\\+]"), "")
            .trim()
    }

    companion object {
        private const val TAG = "ContactsRepository"
    }
}
