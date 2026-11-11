package com.persistor

fun mb(bytes: Long) = "%.1f МБ".format(bytes / 1048576.0)

fun subjectFor(template: String, zipName: String) =
    template.replace("{date}", zipName.removeSuffix(".zip"))
