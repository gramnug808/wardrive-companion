package art.phoenix.wardrive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Capture(
    val id: Long = 0, val takenAt: Long = 0, val lat: Double = 0.0, val lon: Double = 0.0,
    val plate: String = "", val confirmed: Boolean = false,
    val photoPath: String = "", val ssids: String = "", val radios: String = ""
)

data class Network(
    val bssid: String, val ssid: String, val caps: String = "", val channel: Int = 0,
    val rssi: Int = -100, val lat: Double = 0.0, val lon: Double = 0.0,
    val firstSeen: Long = 0, val source: String = ""
)

data class Mark(val id: Long = 0, val lat: Double, val lon: Double, val note: String, val at: Long = 0)

class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "wardrive.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE captures(id INTEGER PRIMARY KEY AUTOINCREMENT, taken_at INTEGER, lat REAL, lon REAL, plate TEXT, confirmed INTEGER, photo_path TEXT, ssids TEXT, radios TEXT)")
        db.execSQL("CREATE TABLE networks(bssid TEXT PRIMARY KEY, ssid TEXT, caps TEXT, channel INTEGER, rssi INTEGER, lat REAL, lon REAL, first_seen INTEGER, source TEXT)")
        db.execSQL("CREATE TABLE marks(id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL, lon REAL, note TEXT, at INTEGER)")
        db.execSQL("CREATE TABLE watchlist(plate TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE kml_points(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lat REAL, lon REAL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS kml_points(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lat REAL, lon REAL)")
        }
    }

    fun addCapture(c: Capture): Long {
        val v = ContentValues().apply {
            put("taken_at", c.takenAt); put("lat", c.lat); put("lon", c.lon)
            put("plate", c.plate); put("confirmed", if (c.confirmed) 1 else 0)
            put("photo_path", c.photoPath); put("ssids", c.ssids); put("radios", c.radios)
        }
        return writableDatabase.insert("captures", null, v)
    }

    fun listCaptures(limit: Int = 500): List<Capture> {
        val out = mutableListOf<Capture>()
        readableDatabase.rawQuery(
            "SELECT id,taken_at,lat,lon,plate,confirmed,photo_path,ssids,radios FROM captures ORDER BY taken_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cur ->
            while (cur.moveToNext()) out.add(
                Capture(cur.getLong(0), cur.getLong(1), cur.getDouble(2), cur.getDouble(3),
                    cur.getString(4) ?: "", cur.getInt(5) == 1, cur.getString(6) ?: "",
                    cur.getString(7) ?: "", cur.getString(8) ?: "")
            )
        }
        return out
    }

    fun searchCaptures(q: String): List<Capture> {
        val like = "%$q%"
        val out = mutableListOf<Capture>()
        readableDatabase.rawQuery(
            "SELECT id,taken_at,lat,lon,plate,confirmed,photo_path,ssids,radios FROM captures WHERE plate LIKE ? OR ssids LIKE ? OR radios LIKE ? ORDER BY taken_at DESC LIMIT 200",
            arrayOf(like, like, like)
        ).use { cur ->
            while (cur.moveToNext()) out.add(
                Capture(cur.getLong(0), cur.getLong(1), cur.getDouble(2), cur.getDouble(3),
                    cur.getString(4) ?: "", cur.getInt(5) == 1, cur.getString(6) ?: "",
                    cur.getString(7) ?: "", cur.getString(8) ?: "")
            )
        }
        return out
    }

    fun confirmPlate(id: Long) {
        writableDatabase.execSQL("UPDATE captures SET confirmed=1 WHERE id=?", arrayOf<Any?>(id))
    }

    fun correctPlate(id: Long, plate: String) {
        writableDatabase.execSQL("UPDATE captures SET plate=?, confirmed=1 WHERE id=?", arrayOf<Any?>(plate, id))
    }

    fun upsertNetwork(n: Network) {
        val v = ContentValues().apply {
            put("bssid", n.bssid); put("ssid", n.ssid); put("caps", n.caps)
            put("channel", n.channel); put("rssi", n.rssi); put("lat", n.lat); put("lon", n.lon)
            put("first_seen", n.firstSeen); put("source", n.source)
        }
        writableDatabase.insertWithOnConflict("networks", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun listNetworks(limit: Int = 500): List<Network> {
        val out = mutableListOf<Network>()
        readableDatabase.rawQuery(
            "SELECT bssid,ssid,caps,channel,rssi,lat,lon,first_seen,source FROM networks ORDER BY first_seen DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cur ->
            while (cur.moveToNext()) out.add(
                Network(cur.getString(0) ?: "", cur.getString(1) ?: "", cur.getString(2) ?: "",
                    cur.getInt(3), cur.getInt(4), cur.getDouble(5), cur.getDouble(6),
                    cur.getLong(7), cur.getString(8) ?: "")
            )
        }
        return out
    }

    fun networkCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM networks", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun addMark(m: Mark): Long {
        val v = ContentValues().apply {
            put("lat", m.lat); put("lon", m.lon); put("note", m.note); put("at", m.at)
        }
        return writableDatabase.insert("marks", null, v)
    }

    fun listMarks(): List<Mark> {
        val out = mutableListOf<Mark>()
        readableDatabase.rawQuery("SELECT id,lat,lon,note,at FROM marks ORDER BY at DESC LIMIT 200", null).use { cur ->
            while (cur.moveToNext()) out.add(Mark(cur.getLong(0), cur.getDouble(1), cur.getDouble(2), cur.getString(3) ?: "", cur.getLong(4)))
        }
        return out
    }

    fun addWatch(plate: String) {
        val v = ContentValues().apply { put("plate", Plates.normalise(plate)) }
        writableDatabase.insertWithOnConflict("watchlist", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun watchlist(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT plate FROM watchlist ORDER BY plate", null).use { cur ->
            while (cur.moveToNext()) out.add(cur.getString(0) ?: "")
        }
        return out
    }

    fun isWatched(plate: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM watchlist WHERE plate=?", arrayOf(Plates.normalise(plate))).use {
            return it.moveToFirst()
        }
    }

    fun saveKmlPoints(points: List<Triple<String, Double, Double>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((name, lon, lat) in points) {
                val v = ContentValues().apply { put("name", name); put("lat", lat); put("lon", lon) }
                db.insert("kml_points", null, v)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun listKmlPoints(): List<Triple<String, Double, Double>> {
        val out = mutableListOf<Triple<String, Double, Double>>()
        readableDatabase.rawQuery("SELECT name,lat,lon FROM kml_points ORDER BY id LIMIT 2000", null).use { cur ->
            while (cur.moveToNext()) out.add(Triple(cur.getString(0) ?: "", cur.getDouble(2), cur.getDouble(1)))
        }
        return out
    }

    fun clearKmlPoints() { writableDatabase.execSQL("DELETE FROM kml_points") }

    fun stats(): Triple<Int, Int, Int> {
        val db = readableDatabase
        fun count(t: String): Int = db.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return Triple(count("captures"), count("networks"), count("marks"))
    }
}

/** Import WiGLE / Kismet / airodump CSV text into networks. Returns (imported, skipped). */
fun importNetworksCsv(db: DbHelper, text: String, now: Long): Pair<Int, Int> {
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.isEmpty()) return Pair(0, 0)
    val dialect = CsvSniff.dialect(lines.first())
    if (dialect == CsvDialect.UNKNOWN) return Pair(0, lines.size)
    var imported = 0; var skipped = 0
    for (line in lines.drop(1)) {
        val f = CsvSniff.split(line)
        try {
            val (bssid, ssid, channel, rssi) = when (dialect) {
                CsvDialect.WIGLE -> Quad(f.getOrNull(0) ?: "", f.getOrNull(1) ?: "", f.getOrNull(4)?.toIntOrNull() ?: 0, f.getOrNull(5)?.toIntOrNull() ?: -100)
                CsvDialect.KISMET -> Quad(f.getOrNull(0) ?: "", f.getOrNull(2) ?: "", f.getOrNull(3)?.toIntOrNull() ?: 0, f.getOrNull(4)?.toIntOrNull() ?: -100)
                CsvDialect.AIRODUMP -> Quad(f.getOrNull(0) ?: "", f.getOrNull(13) ?: "", f.getOrNull(3)?.toIntOrNull() ?: 0, f.getOrNull(8)?.toIntOrNull() ?: -100)
                CsvDialect.UNKNOWN -> Quad("", "", 0, -100)
            }
            if (bssid.isBlank() || bssid == "BSSID") { skipped++; continue }
            db.upsertNetwork(Network(bssid.uppercase(), ssid, "", channel, rssi, 0.0, 0.0, now, dialect.name.lowercase()))
            imported++
        } catch (e: Exception) { skipped++ }
    }
    return Pair(imported, skipped)
}

private data class Quad(val a: String, val b: String, val c: Int, val d: Int)
