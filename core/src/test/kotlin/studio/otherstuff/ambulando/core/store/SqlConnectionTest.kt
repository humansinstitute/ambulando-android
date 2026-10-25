package studio.otherstuff.ambulando.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlConnectionTest {
    @Test fun bindsTypesRollsBackAndHasFts5() {
        SqlConnection.memory().use { db ->
            db.execute("CREATE TABLE t(a INTEGER, b TEXT, c BLOB, d REAL)")
            db.run("INSERT INTO t VALUES(?,?,?,?)", 7L, "x", byteArrayOf(1, 2), 1.5)
            runCatching { db.transaction { db.run("INSERT INTO t VALUES(8,'y',NULL,0)"); error("boom") } }
            assertEquals(1L, db.longValue("SELECT count(*) FROM t"))
            assertEquals(listOf(7L to "x"), db.query("SELECT a,b FROM t WHERE a = ?", 7) { it.long(0) to it.text(1) })
            db.execute("CREATE VIRTUAL TABLE f USING fts5(body)")
            db.run("INSERT INTO f(body) VALUES('hello world')")
            assertEquals(1L, db.longValue("SELECT count(*) FROM f WHERE f MATCH ?", "\"hel\"*"))
            db.userVersion = 5
            assertTrue(db.userVersion == 5)
        }
    }
}
