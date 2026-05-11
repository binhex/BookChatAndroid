package com.bookchat.data.settings

data class AppSettings(
    val ircServer: String = "irc.irchighway.net",
    val ircPort: Int = 6667,
    val ircUseSsl: Boolean = false,
    val ircChannel: String = "#ebooks",
    val ircNickname: String = "",
    val ircPassword: String = "",
    val watchFolderUri: String = "",
    val driveAccountName: String = "",
    val driveFolderId: String = "",
)
