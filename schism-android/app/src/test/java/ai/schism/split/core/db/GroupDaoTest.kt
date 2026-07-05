package ai.schism.split.core.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupDaoTest {
    private lateinit var db: SchismDb
    private lateinit var dao: GroupDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SchismDb::class.java,
        ).allowMainThreadQueries().build()
        dao = db.groupDao()
    }

    @After
    fun tearDown() = db.close()

    private fun group(id: String, name: String) =
        GroupEntity(id, name, "", "$", "USD", "2026-07-05T00:00:00Z")

    @Test
    fun upsertAndObserveGroupWithParticipants() = runTest {
        dao.upsertGroupWithParticipants(
            group("g1", "Trip"),
            listOf(ParticipantEntity("p1", "g1", "A"), ParticipantEntity("p2", "g1", "B")),
        )

        val groups = dao.observeGroups().first()
        assertEquals(1, groups.size)
        assertEquals("Trip", groups[0].group.name)
        assertEquals(2, groups[0].participants.size)
    }

    @Test
    fun refreshPreservesLocalFlags() = runTest {
        dao.upsertGroupWithParticipants(group("g1", "Trip"), listOf(ParticipantEntity("p1", "g1", "A")))
        dao.setFavorite("g1", true)
        dao.setActiveParticipant("g1", "p1")

        // simulate a server refresh replacing the group row
        dao.upsertGroupWithParticipants(group("g1", "Trip Renamed"), listOf(ParticipantEntity("p1", "g1", "A")))

        val g = dao.observeGroup("g1").first()!!
        assertEquals("Trip Renamed", g.group.name)
        assertTrue("favorite must survive refresh", g.group.isFavorite)
        assertEquals("p1", g.group.activeParticipantId)
    }
}
