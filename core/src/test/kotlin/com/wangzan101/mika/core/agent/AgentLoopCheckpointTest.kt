package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunInbox
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.testing.assertAnswered
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("c${++seq}", name, args)
private fun tools(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
private fun waits(n: Int) = (1..n).map { tools(call("wait", """{"seconds":${1 + it / 10.0}}""")) }.toTypedArray()
private const val DONE = "好了"

/** 不限步数的任务每做满 N 步先问用户要不要继续(#158):继续、停下、打了别的话、没人回答、无人值守。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopCheckpointTest {
    private fun loop(m: FakeModel, every: Int? = 2, maxSteps: Int = AgentLoop.UNLIMITED_STEPS) =
        AgentLoop(m, toolDispatcher(FakeScreen()), "BASE", maxSteps = maxSteps, stepCheckpoint = every)

    /** 把前面几步的 wait 走完,停在检查点上。 */
    private fun TestScope.reachCheckpoint(l: AgentLoop, done: Int) {
        advanceTimeBy(10_000); runCurrent()
        assertEquals(TaskState.WaitingInput(TurnPolicy.checkpointPrompt(done), TurnPolicy.CHECKPOINT_OPTIONS), l.state.value)
    }

    @Test fun continueKeepsGoingAndAsksAgainAtNextCheckpoint() = runTest {
        val m = FakeModel(*waits(4), answerReply(DONE), answerReply(DONE))
        val l = loop(m)
        val run = async { l.run("t") }
        reachCheckpoint(l, 2)
        assertEquals("waiting does not call the model", 2, m.requests.size)
        assertTrue(l.answerInput(TurnPolicy.CHECKPOINT_CONTINUE))
        reachCheckpoint(l, 4)
        assertTrue(l.answerInput(TurnPolicy.CHECKPOINT_CONTINUE))
        assertAnswered(DONE, run.await())
        assertEquals(5, m.requests.size)
    }

    @Test fun stopGoesThroughFinalTurn() = runTest {
        val m = FakeModel(*waits(2), ModelReply(listOf(Part.Text("")), StopReason.EndTurn, null))
        val l = loop(m)
        val run = async { l.run("t") }
        reachCheckpoint(l, 2)
        l.answerInput(TurnPolicy.CHECKPOINT_STOP)
        assertEquals(StopRules.checkpoint(2, answered = true), run.await())
        // 最后一轮和步数用完时一样:只给放弃,做完了用文字回答
        assertEquals(listOf("end_not_done"), m.offeredTools.last())
        assertEquals(3, m.requests.size)
    }

    @Test fun stopStillLetsTheModelReportWhatItFound() = runTest {
        val m = FakeModel(*waits(2), tools(call("end_not_done", """{"message":"翻了两页还没找到"}""")))
        val l = loop(m)
        val run = async { l.run("t") }
        reachCheckpoint(l, 2)
        l.answerInput(TurnPolicy.CHECKPOINT_STOP)
        assertEquals(TaskState.Failed("翻了两页还没找到", FailKind.GaveUp), run.await())
    }

    @Test fun otherWordsContinueAsAnInterjection() = runTest {
        val m = FakeModel(*waits(2), answerReply(DONE), answerReply(DONE))
        val l = loop(m)
        val run = async { l.run("t") }
        reachCheckpoint(l, 2)
        l.answerInput("换搜索框试试")
        assertAnswered(DONE, run.await())
        val said = m.requests[2].flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        assertTrue(said.toString(), said.any { it.startsWith(AgentLoop.STEER_MARKER) && "换搜索框试试" in it })
    }

    @Test fun noAnswerStops() = runTest {
        val m = FakeModel(*waits(2), ModelReply(listOf(Part.Text("")), StopReason.EndTurn, null))
        assertEquals(StopRules.checkpoint(2, answered = false), loop(m).run("t"))
    }

    @Test fun unattendedStopsWithoutWaiting() = runTest {
        val m = FakeModel(*waits(2), ModelReply(listOf(Part.Text("")), StopReason.EndTurn, null))
        val spec = RunSpec(RunId("r"), "t", "th", provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended))
        assertEquals(StopRules.checkpoint(2, answered = false), loop(m).run(spec, RunInbox()))
        assertTrue("did not wait for an answer: $currentTime", currentTime < AgentLoop.DEFAULT_INPUT_TIMEOUT_MS)
    }

    @Test fun offOrStepBudgetNeverAsks() = runTest {
        assertAnswered(DONE, loop(FakeModel(*waits(3), answerReply(DONE)), every = null).run("t"))
        // 给了步数上限(评测、子任务)时照旧按上限,不问
        val r = loop(FakeModel(*waits(3), ModelReply(listOf(Part.Text("")), StopReason.EndTurn, null)), every = 1, maxSteps = 3).run("t")
        assertEquals(StopRules.stepLimit(3), r)
    }
}
