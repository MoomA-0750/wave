package xyz.mooma0750.wave.connect2

import org.junit.Test
import xyz.mooma0750.wave.connect2.PlayButtonPolicy.Decision
import xyz.mooma0750.wave.connect2.PlayButtonPolicy.WaitOutcome
import kotlin.test.assertEquals

/** D-9 の判断（再生ボタン・曲の選択）。 */
class PlayButtonPolicyTest {
    private fun play(
        newSelection: Boolean = false,
        retreated: Boolean = true,
        inHousehold: Boolean = true,
        stateSettled: Boolean = true,
        hasAccountTag: Boolean = true,
        remotePlaying: Boolean = true,
        foreground: Boolean = true,
    ) = PlayButtonPolicy.onPlay(newSelection, retreated, inHousehold, stateSettled, hasAccountTag, remotePlaying, foreground)

    @Test
    fun choosingASongIsAlwaysANewPlayback() {
        // 引き取られた端末で曲を選んでも、相手のキューに置き換えない（レビュー p6b34 高-1）
        assertEquals(Decision.Claim, play(newSelection = true))
        assertEquals(Decision.Claim, play(newSelection = true, stateSettled = false, hasAccountTag = false))
    }

    @Test
    fun playButtonOnADeviceThatWasNotRetreatedJustClaims() {
        assertEquals(Decision.Claim, play(retreated = false, remotePlaying = true))
        assertEquals(Decision.Claim, play(inHousehold = false))
    }

    @Test
    fun retreatedPlayButtonWaitsUntilTheStateAndAccountAreKnown() {
        // 切れている間にメモリに残った古い状態で決めない（再レビュー 高-A）
        assertEquals(Decision.Wait, play(stateSettled = false, remotePlaying = false))
        // アカウントを確かめるまで「別のアカウントの再生」と区別できない（再レビュー 中-B）
        assertEquals(Decision.Wait, play(hasAccountTag = false, remotePlaying = false))
    }

    @Test
    fun retreatedPlayButtonTakesOverInTheForegroundAndIsBlockedInTheBackground() {
        assertEquals(Decision.TakeOver, play(foreground = true))
        assertEquals(Decision.BlockRemotePlaying, play(foreground = false))
        // 相手が鳴っていなければ（落ちた・止めた）鳴らす
        assertEquals(Decision.Claim, play(remotePlaying = false, foreground = false))
    }

    @Test
    fun theWaitDoesNothingWhenTheUserActedInTheMeantime() {
        // 待っている間に曲を選んだ・一時停止した（再レビュー 中-C）
        assertEquals(Decision.Nothing, PlayButtonPolicy.afterWait(WaitOutcome.Ready, intentUnchanged = false, stillRetreated = true, remotePlaying = false, foreground = true))
        // 待っている間に引き取れた（もう退いた端末でない）
        assertEquals(Decision.Nothing, PlayButtonPolicy.afterWait(WaitOutcome.Ready, intentUnchanged = true, stillRetreated = false, remotePlaying = false, foreground = true))
    }

    @Test
    fun theWaitResumesOnlyWhenNoOneElseIsPlaying() {
        assertEquals(Decision.ResumeAndClaim, PlayButtonPolicy.afterWait(WaitOutcome.Ready, intentUnchanged = true, stillRetreated = true, remotePlaying = false, foreground = false))
        assertEquals(Decision.TakeOver, PlayButtonPolicy.afterWait(WaitOutcome.Ready, intentUnchanged = true, stillRetreated = true, remotePlaying = true, foreground = true))
        assertEquals(Decision.BlockRemotePlaying, PlayButtonPolicy.afterWait(WaitOutcome.Ready, intentUnchanged = true, stillRetreated = true, remotePlaying = true, foreground = false))
    }

    @Test
    fun theWaitTellsWhyItCouldNotDecide() {
        // つながらないときは鳴らさずに知らせる（計画 D-9）。理由ごとに文言が変わる
        assertEquals(Decision.NotifyUnreachable, PlayButtonPolicy.afterWait(WaitOutcome.Unreachable, intentUnchanged = true, stillRetreated = true, remotePlaying = false, foreground = true))
        assertEquals(Decision.NotifyNotMember, PlayButtonPolicy.afterWait(WaitOutcome.NotMember, intentUnchanged = true, stillRetreated = true, remotePlaying = false, foreground = true))
        assertEquals(Decision.NotifyUnverified, PlayButtonPolicy.afterWait(WaitOutcome.Unverified, intentUnchanged = true, stillRetreated = true, remotePlaying = false, foreground = true))
    }
}
