package com.persistor

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Открывает Gmail с готовым письмом: получатели, тема и вложение уже заполнены.
 * Нажать «Отправить» нужно самому: Android не позволяет приложениям слать почту втихую.
 */
object Mailer {
    private const val GMAIL = "com.google.android.gm"

    fun sendIntent(shareUri: Uri, to: List<String>, subject: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, to.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, shareUri)
            clipData = ClipData.newRawUri("", shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun gmailIntent(shareUri: Uri, to: List<String>, subject: String): Intent =
        sendIntent(shareUri, to, subject).apply { setPackage(GMAIL) }

    fun launch(context: Context, shareUri: Uri, to: List<String>, subject: String) {
        try {
            context.startActivity(gmailIntent(shareUri, to, subject))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(sendIntent(shareUri, to, subject), "Отправить архив"))
        }
    }
}
