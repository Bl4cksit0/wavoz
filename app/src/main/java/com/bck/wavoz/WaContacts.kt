package com.bck.wavoz

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale

/** Contactos de la agenda que tienen WhatsApp, con su número internacional. */
object WaContacts {
    data class C(val name: String, val phone: String)

    @Volatile var list: List<C> = emptyList(); private set

    private val MIMES = arrayOf(
        "vnd.android.cursor.item/vnd.com.whatsapp.profile",
        "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile")

    fun load(c: Context) {
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        Thread {
            try {
                val out = LinkedHashMap<String, C>()
                c.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1),
                    "${ContactsContract.Data.MIMETYPE} IN (?,?)", MIMES, null)?.use { cur ->
                    while (cur.moveToNext()) {
                        val name = cur.getString(0) ?: continue
                        val jid = cur.getString(1) ?: continue
                        val phone = jid.substringBefore("@").filter { it.isDigit() }
                        if (phone.length >= 8) out.putIfAbsent(phone, C(name, phone))
                    }
                }
                if (out.isEmpty()) {
                    // Respaldo: todos los teléfonos de la agenda, normalizados al formato internacional.
                    val iso = (c.getSystemService(TelephonyManager::class.java).simCountryIso ?: "")
                        .ifEmpty { Locale.getDefault().country }.uppercase()
                    c.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                        null, null, null)?.use { cur ->
                        while (cur.moveToNext()) {
                            val name = cur.getString(0) ?: continue
                            val num = cur.getString(1) ?: continue
                            val e164 = PhoneNumberUtils.formatNumberToE164(num, iso) ?: continue
                            val phone = e164.filter { it.isDigit() }
                            out.putIfAbsent(phone, C(name, phone))
                        }
                    }
                }
                list = out.values.toList()
                Log.d("WaVoz", "contacts: ${list.size}")
            } catch (e: Exception) { Log.w("WaVoz", "contacts", e) }
        }.start()
    }
}
