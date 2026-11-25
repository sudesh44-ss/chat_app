package com.nexa.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexa.app.core.sms.SmsSchemaDdl
import com.nexa.app.data.local.NexaDatabase
import com.nexa.app.data.local.entity.SmsInboundPartEntity
import com.nexa.app.data.local.entity.SmsPacketEntity
import com.nexa.app.data.local.entity.SmsSessionEntity
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Module 3 — real SQLCipher database integration (requires a device/emulator).
 *
 * Verifies what the JVM tests cannot: SQLCipher open/close, the v28 schema as actually created,
 * DAO semantics, unique constraints, duplicate handling, and crash recovery of the multipart
 * buffer across a database close/reopen.
 *
 * Run with: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SmsDatabaseInstrumentedTest {

    private lateinit var context: Context
    private lateinit var db: NexaDatabase
    private val passphrase = "module3-instrumented-test-passphrase".toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("nexa_module3_test.db")
        db = Room.databaseBuilder(context, NexaDatabase::class.java, "nexa_module3_test.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(NexaDatabase.MIGRATION_27_28)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("nexa_module3_test.db")
    }

    private fun session(peer: String = "NX-BOB-0002", counter: Long = 0L) = SmsSessionEntity(
        peerNxId = peer,
        peerProvisioningPublicKeyHex = "ab".repeat(32),
        localProvisioningPublicKeyHex = "cd".repeat(32),
        keyVersion = 1,
        establishedAt = 1_000L,
        expiresAt = 2_000L,
        sendCounter = counter,
        lastUsedAt = 1_000L
    )

    @Test
    fun v28SchemaContainsAllSmsTablesAndColumns() {
        val raw = db.openHelper.writableDatabase
        SmsSchemaDdl.statements
            .filter { it.contains("CREATE TABLE") }
            .forEach { statement ->
                val table = Regex("CREATE TABLE IF NOT EXISTS `([a-z_]+)`").find(statement)!!.groupValues[1]
                val columns = mutableSetOf<String>()
                raw.query("PRAGMA table_info(`$table`)").use { cursor ->
                    while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("$table was not created", columns.isNotEmpty())
                Regex("^\\s*`([A-Za-z0-9_]+)`", RegexOption.MULTILINE).findAll(statement)
                    .map { it.groupValues[1] }
                    .filterNot { it == "PRIMARY" }
                    .forEach { column ->
                        assertTrue("$table.$column missing from real schema", columns.contains(column))
                    }
            }
    }

    @Test
    fun sessionCounterPersistsAcrossCloseAndReopen() = runBlocking {
        db.smsSessionDao().upsertSession(session(counter = 7))
        assertEquals(7L, db.smsSessionDao().getSession("NX-BOB-0002")!!.sendCounter)
        db.smsSessionDao().updateSendCounter("NX-BOB-0002", 8, 1_100)
        assertEquals(8L, db.smsSessionDao().getSession("NX-BOB-0002")!!.sendCounter)
        db.close()

        db = Room.databaseBuilder(context, NexaDatabase::class.java, "nexa_module3_test.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(NexaDatabase.MIGRATION_27_28)
            .allowMainThreadQueries()
            .build()
        assertEquals(8L, db.smsSessionDao().getSession("NX-BOB-0002")!!.sendCounter)
    }

    @Test
    fun replayWindowFieldsPersistAcrossReopen() = runBlocking {
        db.smsSessionDao().upsertSession(session())
        db.smsSessionDao().updateReceiveWindow("NX-BOB-0002", 12, 0b1011L, 1_200)
        db.close()
        db = Room.databaseBuilder(context, NexaDatabase::class.java, "nexa_module3_test.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(NexaDatabase.MIGRATION_27_28)
            .allowMainThreadQueries()
            .build()

        val reopened = db.smsSessionDao().getSession("NX-BOB-0002")!!
        assertEquals(12L, reopened.recvHighestCounter)
        assertEquals(0b1011L, reopened.recvBitmap)
    }

    @Test
    fun inboundPartsSurviveReopenAndRejectDuplicateIndex() = runBlocking {
        val dao = db.smsInboundPartDao()
        dao.insertIfAbsent(SmsInboundPartEntity(messageId = "m1", senderNxId = "NX-BOB-0002", partIndex = 0, totalParts = 2, payloadBase64 = "AAA", receivedAt = 1))
        db.close()
        db = Room.databaseBuilder(context, NexaDatabase::class.java, "nexa_module3_test.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(NexaDatabase.MIGRATION_27_28)
            .allowMainThreadQueries()
            .build()

        // Crash recovery: the first part is still there; the second completes the message.
        assertEquals(1, db.smsInboundPartDao().partsFor("m1", "NX-BOB-0002").size)
        db.smsInboundPartDao().insertIfAbsent(
            SmsInboundPartEntity(messageId = "m1", senderNxId = "NX-BOB-0002", partIndex = 0, totalParts = 2, payloadBase64 = "ZZZ", receivedAt = 2)
        )
        assertEquals("duplicate part index must be ignored, not overwritten", "AAA",
            db.smsInboundPartDao().partsFor("m1", "NX-BOB-0002").first().payloadBase64)

        db.smsInboundPartDao().insertIfAbsent(
            SmsInboundPartEntity(messageId = "m1", senderNxId = "NX-BOB-0002", partIndex = 1, totalParts = 2, payloadBase64 = "BBB", receivedAt = 3)
        )
        assertEquals(2, db.smsInboundPartDao().partsFor("m1", "NX-BOB-0002").size)

        db.smsInboundPartDao().deleteMessage("m1", "NX-BOB-0002")
        assertTrue(db.smsInboundPartDao().partsFor("m1", "NX-BOB-0002").isEmpty())
    }

    @Test
    fun packetStateTransitionsAndQueriesWork() = runBlocking {
        val dao = db.smsPacketDao()
        val packet = SmsPacketEntity(
            messageId = "uuid-1",
            packetId = "nx1-NX-BOB-0002-abc",
            peerNxId = "NX-BOB-0002",
            direction = SmsPacketEntity.DIRECTION_OUTBOUND,
            keyVersion = 1,
            sendCounter = 0,
            totalParts = 1,
            segmentCount = 3,
            handoffState = "QUEUED",
            createdAt = 100,
            updatedAt = 100
        )
        dao.upsert(packet)
        assertEquals(1, dao.getPendingOutbound().size)

        dao.updateState("uuid-1", "HANDED_OFF", 200, null)
        assertEquals("HANDED_OFF", dao.getByMessageId("uuid-1")!!.handoffState)
        assertNotNull(dao.getByPacketId("nx1-NX-BOB-0002-abc"))

        dao.updateState("uuid-1", "SENT_UNCONFIRMED", 300, "MODE_A_UNCONFIRMED")
        assertEquals("MODE_A_UNCONFIRMED", dao.getByMessageId("uuid-1")!!.failureKind)
        assertEquals(1, dao.purgeSettledBefore(10_000))
        assertNull(dao.getByMessageId("uuid-1"))
    }

    @Test
    fun expiredPartsArePurgedWithoutTouchingFreshOnes() = runBlocking {
        val dao = db.smsInboundPartDao()
        dao.insertIfAbsent(SmsInboundPartEntity(messageId = "old", senderNxId = "NX-BOB-0002", partIndex = 0, totalParts = 2, payloadBase64 = "A", receivedAt = 1))
        dao.insertIfAbsent(SmsInboundPartEntity(messageId = "new", senderNxId = "NX-BOB-0002", partIndex = 0, totalParts = 2, payloadBase64 = "B", receivedAt = 10_000))
        assertEquals(1, dao.purgeExpired(5_000))
        assertEquals(0, dao.partsFor("old", "NX-BOB-0002").size)
        assertEquals(1, dao.partsFor("new", "NX-BOB-0002").size)
        assertEquals(1, dao.sessionCountSince(0))
    }
}
