package com.gooserelay.gooserelayvpn.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val debugTiming: Boolean = false,
    val socksHost: String = "127.0.0.1",
    val socksPort: Int = 1080,
    val socksUser: String = "",
    val socksPass: String = "",
    val googleHost: String = "216.239.38.120",
    val sniJson: String = "[\"www.google.com\",\"mail.google.com\",\"accounts.google.com\"]",
    val scriptKeysText: String = "",
    val tunnelKey: String = "",
    val coalesceStepMs: Int = 0,
    val idleSlotsPerBucket: Int = 2,
    val remoteUrl: String? = null,
    val isSelected: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
