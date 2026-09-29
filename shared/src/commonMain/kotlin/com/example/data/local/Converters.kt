package com.example.data.local

import androidx.room.TypeConverter
import com.example.data.model.CallType
import com.example.data.model.RegistrationStatus
import com.example.data.model.SipTransport

class Converters {
    @TypeConverter
    fun fromSipTransport(value: SipTransport): String = value.name

    @TypeConverter
    fun toSipTransport(value: String): SipTransport = runCatching {
        SipTransport.valueOf(value)
    }.getOrDefault(SipTransport.UDP)

    @TypeConverter
    fun fromRegistrationStatus(value: RegistrationStatus): String = value.name

    @TypeConverter
    fun toRegistrationStatus(value: String): RegistrationStatus = runCatching {
        RegistrationStatus.valueOf(value)
    }.getOrDefault(RegistrationStatus.UNREGISTERED)

    @TypeConverter
    fun fromCallType(value: CallType): String = value.name

    @TypeConverter
    fun toCallType(value: String): CallType = runCatching {
        CallType.valueOf(value)
    }.getOrDefault(CallType.OUTGOING)
}
