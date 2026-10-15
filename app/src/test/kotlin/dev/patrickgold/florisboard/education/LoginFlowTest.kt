package dev.patrickgold.florisboard.education

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LoginFlowTest : FunSpec({
    val session = EducationalSession("student_001", "jwt", "2099-01-01T00:00:00Z")

    test("blank fields fail immediately without calling the backend") {
        var calls = 0
        val flow = LoginFlow(TestScope(), authenticate = { _, _ -> calls++; Result.success(session) }, onSuccess = {})

        flow.submit("", "1234")
        flow.state.value shouldBe LoginState.Failed(EducationalMessages.EmptyCredentials)

        flow.submit("student_001", " ")
        flow.state.value shouldBe LoginState.Failed(EducationalMessages.EmptyCredentials)
        calls shouldBe 0
    }

    test("success delivers the session and returns to idle") {
        runTest {
            var delivered: EducationalSession? = null
            val flow = LoginFlow(this, authenticate = { _, _ -> Result.success(session) }, onSuccess = { delivered = it })

            flow.submit(" student_001 ", "1234")
            advanceUntilIdle()

            delivered shouldBe session
            flow.state.value shouldBe LoginState.Idle
        }
    }

    test("failure maps the error with the login catalogue") {
        runTest {
            val flow = LoginFlow(this, authenticate = { _, _ -> Result.failure(EducationalHttpException(401, "")) }, onSuccess = {})

            flow.submit("student_001", "0000")
            advanceUntilIdle()

            flow.state.value shouldBe LoginState.Failed(EducationalMessages.login(EducationalHttpException(401, "")))
        }
    }

    test("a failure while persisting the session never leaves the flow loading") {
        runTest {
            val flow = LoginFlow(
                this,
                authenticate = { _, _ -> Result.success(session) },
                onSuccess = { throw IllegalStateException("keystore") },
            )

            flow.submit("student_001", "1234")
            advanceUntilIdle()

            flow.state.value.shouldBeInstanceOf<LoginState.Failed>()
            (flow.state.value as LoginState.Failed).message shouldBe
                EducationalMessages.login(IllegalStateException("keystore"))
        }
    }

    test("a second submit while loading is ignored") {
        runTest(StandardTestDispatcher()) {
            val gate = CompletableDeferred<Result<EducationalSession>>()
            var calls = 0
            val flow = LoginFlow(this, authenticate = { _, _ -> calls++; gate.await() }, onSuccess = {})

            flow.submit("student_001", "1234")
            testScheduler.runCurrent()
            flow.state.value.shouldBeInstanceOf<LoginState.Loading>()
            flow.submit("student_001", "1234")
            testScheduler.runCurrent()

            calls shouldBe 1
            gate.complete(Result.success(session))
            advanceUntilIdle()
            flow.state.value shouldBe LoginState.Idle
        }
    }

    test("a rejected session shows the login with the session-ended notice") {
        val flow = LoginFlow(TestScope(), authenticate = { _, _ -> Result.success(session) }, onSuccess = {})
        flow.sessionEnded()
        flow.state.value shouldBe LoginState.Failed("Tu sesión terminó. Vuelve a iniciar sesión.")
        flow.reset()
        flow.state.value shouldBe LoginState.Idle
    }

    test("the session-ended notice never interrupts a login in progress") {
        runTest(StandardTestDispatcher()) {
            val gate = CompletableDeferred<Result<EducationalSession>>()
            val flow = LoginFlow(this, authenticate = { _, _ -> gate.await() }, onSuccess = {})
            flow.submit("student_001", "1234")
            testScheduler.runCurrent()
            flow.sessionEnded()
            flow.state.value.shouldBeInstanceOf<LoginState.Loading>()
            gate.complete(Result.success(session))
            advanceUntilIdle()
            flow.state.value shouldBe LoginState.Idle
        }
    }

    test("the PIN field keeps only digits, at most four") {
        LoginFlow.sanitizePin("1234") shouldBe "1234"
        LoginFlow.sanitizePin("12345") shouldBe "1234"
        LoginFlow.sanitizePin("1a2-3 4") shouldBe "1234"
        LoginFlow.sanitizePin("abc") shouldBe ""
        LoginFlow.sanitizePin("١٢") shouldBe ""
    }

    test("username is trimmed before authenticating") {
        runTest {
            var seen = ""
            val flow = LoginFlow(this, authenticate = { u, _ -> seen = u; Result.success(session) }, onSuccess = {})
            flow.submit("  student_001 ", "1234")
            advanceUntilIdle()
            seen shouldBe "student_001"
        }
    }
})
