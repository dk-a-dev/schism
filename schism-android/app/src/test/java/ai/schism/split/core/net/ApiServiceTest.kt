package ai.schism.split.core.net

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ApiClient.create(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun listCategoriesParsesResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """[{"id":0,"grouping":"Uncategorized","name":"General"},
                    {"id":7,"grouping":"Food and drink","name":"Groceries"}]""",
            ),
        )

        val cats = api.listCategories()

        assertEquals(2, cats.size)
        assertEquals(0, cats[0].id)
        assertEquals("Groceries", cats[1].name)

        val req: RecordedRequest = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/categories", req.path)
    }

    @Test
    fun createExpenseSendsBodyAndIdempotencyHeader() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"id":"e1","groupId":"g1","title":"Dinner","amount":1000,"paidById":"p1",
                    "splitMode":"EVENLY","paidFor":[{"participantId":"p1","shares":100}]}""",
            ),
        )

        val expense = api.createExpense(
            groupId = "g1",
            body = ExpenseRequest(
                title = "Dinner", amount = 1000, paidById = "p1",
                paidFor = listOf(PaidForDto("p1", 100)),
            ),
            idempotencyKey = "key-1",
        )

        assertEquals("e1", expense.id)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/groups/g1/expenses", req.path)
        assertEquals("key-1", req.getHeader("Idempotency-Key"))
        assertTrue(req.body.readUtf8().contains("\"title\":\"Dinner\""))
    }

    @Test
    fun listGroupsPassesIdsQuery() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        api.listGroups("a,b,c")
        assertEquals("/v1/groups?ids=a%2Cb%2Cc", server.takeRequest().path)
    }
}
