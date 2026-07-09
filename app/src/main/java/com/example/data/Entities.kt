package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "matches")
data class SportMatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sport: String, // Cricket, Football, MotorSports, Wrestling, etc.
    val team1Name: String,
    val team1Logo: String, // URL or descriptor
    val team2Name: String,
    val team2Logo: String, // URL or descriptor
    val time: String, // "05:24:23" or "07:30 PM 27/06/2026"
    val status: String, // Live, Upcoming, Recent
    val streamUrl: String
)

@Entity(tableName = "channels")
data class LiveChannel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String, // VIP, News, Local TV, Sports
    val logoUrl: String,
    val streamUrl: String,
    val streamUrl2: String = "",
    val streamUrl3: String = "",
    val streamUrl4: String = "",
    val streamUrl5: String = ""
)

@Entity(tableName = "highlights")
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String, // "Football | FIFA World Cup"
    val team1Name: String, // "Mexico"
    val team1Logo: String,
    val team2Name: String, // "South Africa"
    val team2Logo: String,
    val date: String, // "11/06/2026"
    val streamUrl: String
)

@Entity(tableName = "notices")
data class Notice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val active: Boolean = true
)

@Entity(tableName = "banner_ads")
data class BannerAd(
    @PrimaryKey val id: Long = 1,
    val mediaType: String = "image", // "image", "gif", "video"
    val mediaUrl: String = "",
    val clickUrl: String = "",
    val enabled: Boolean = false
)
