package mentor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 2026-11-24, 15:31 MSK: a photo arrived while the bot was being redeployed. The old process had already marked it as
 * handled, was killed while the image model was thinking, and the message was lost without an answer.
 */
class RestartRedeliveryTest {
    @Test
    void anUpdateCutOffByARestartIsAskedForAgainOnce() {
        var state = new MentorState();
        state.updateOffset = 101; // update 100 was taken from Telegram…
        state.inFlightUpdate = 100; // …and the process died before answering

        assertThat(MentorBot.redeliverCutOffUpdate(state)).isTrue();
        assertThat(state.updateOffset).isEqualTo(100);
        assertThat(state.inFlightUpdate).isZero();

        // The same update kills the bot again: it is not requested a third time.
        state.updateOffset = 101;
        state.inFlightUpdate = 100;
        assertThat(MentorBot.redeliverCutOffUpdate(state)).isFalse();
        assertThat(state.updateOffset).isEqualTo(101);
    }

    @Test
    void aCleanStopChangesNothing() {
        var state = new MentorState();
        state.updateOffset = 101;
        assertThat(MentorBot.redeliverCutOffUpdate(state)).isFalse();
        assertThat(state.updateOffset).isEqualTo(101);
    }

    @Test
    void anOldInFlightMarkBehindNewerUpdatesIsDropped() {
        var state = new MentorState();
        state.updateOffset = 105;
        state.inFlightUpdate = 100;
        assertThat(MentorBot.redeliverCutOffUpdate(state)).isFalse();
        assertThat(state.updateOffset).isEqualTo(105);
        assertThat(state.inFlightUpdate).isZero();
    }
}
