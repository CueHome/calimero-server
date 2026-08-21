/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2010, 2025 B. Malinowsky
    Copyright (c) 2026 CueHome

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/
package io.calimero.server.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import io.calimero.GroupAddress;
import io.calimero.KNXIllegalArgumentException;
import io.calimero.IndividualAddress;
import io.calimero.Priority;
import io.calimero.cemi.CEMILData;
import io.calimero.cemi.CEMILDataEx;

/**
 * Pins the behaviour of {@code KnxServerGateway.adjustHopCount}, which is a Cue deviation from upstream
 * {@code calimero-project/calimero-server}.
 * <p>
 * <b>These tests record what the fork does today. They are not an endorsement of it.</b> The hop-count-0
 * branch in particular removes the KNX routing-counter loop prevention that upstream implements, and is
 * tracked as an open defect. See {@code docs/knx/HOP-COUNT-PATCH.md} for the full comparison, the evidence
 * trail, and the open items.
 * <p>
 * The point of pinning it is that the fork is behind upstream and will eventually be rebased or merged.
 * Without these tests, a rebase that silently reverted this method -- or silently kept it -- would look
 * identical in review. With them, either outcome is a visible, deliberate decision.
 * <p>
 * The method is private and is exercised by reflection on purpose: widening its visibility would be a
 * source change to the very code under test, and a rebase that changes the signature should fail loudly
 * here rather than quietly compile against a different method.
 */
class KnxServerGatewayHopCountTest
{
	private static final IndividualAddress SRC = new IndividualAddress(1, 1, 1);
	private static final GroupAddress DST = new GroupAddress(1, 1, 1);
	private static final byte[] SHORT_TPDU = { 0, (byte) 0x80 };

	private static CEMILData adjust(final CEMILData msg)
	{
		try {
			final Method m = KnxServerGateway.class.getDeclaredMethod("adjustHopCount", CEMILData.class);
			m.setAccessible(true);
			return (CEMILData) m.invoke(null, msg);
		}
		catch (final NoSuchMethodException e) {
			throw new AssertionError("adjustHopCount(CEMILData) is gone or changed shape -- if this happened "
					+ "during a rebase, read docs/knx/HOP-COUNT-PATCH.md before deciding what to do", e);
		}
		catch (final InvocationTargetException e) {
			throw new AssertionError("adjustHopCount threw", e.getCause());
		}
		catch (final IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] tpdu(final int length)
	{
		final byte[] b = new byte[length];
		b[1] = (byte) 0x80;
		return b;
	}

	/** In calimero a cleared broadcast flag is what marks a frame as system broadcast. */
	private static CEMILDataEx systemBroadcast(final int hopCount)
	{
		return new CEMILDataEx(CEMILData.MC_LDATA_IND, SRC, DST, SHORT_TPDU, Priority.LOW, true, false, false,
				hopCount);
	}

	/**
	 * Branch 1 -- a system broadcast is forwarded untouched, hop count included. Upstream has no such early
	 * return; it is part of the Cue deviation.
	 */
	@Test
	void systemBroadcastIsPassedThroughUnchanged()
	{
		final CEMILDataEx frame = systemBroadcast(6);
		assertTrue(frame.isSystemBroadcast(), "fixture must actually be a system broadcast");

		final CEMILData out = adjust(frame);

		assertSame(frame, out, "system broadcast must be forwarded as the same instance");
		assertEquals(6, out.getHopCount(), "system broadcast hop count must not be decremented");
	}

	/**
	 * Branch 2 -- a hop-count-0 frame is forwarded, un-decremented.
	 * <p>
	 * <b>This is the deviation that matters.</b> Upstream logs a WARNING and returns {@code null} here, which
	 * discards the frame; that discard is the KNX routing-counter loop prevention. The fork forwards it
	 * instead, so an exhausted-hop telegram keeps circulating if a routing loop exists. Tracked as an open
	 * MEDIUM defect. This test pins the current behaviour so that changing it has to be deliberate.
	 */
	@Test
	void hopCountZeroIsForwardedInsteadOfDiscarded()
	{
		final CEMILData frame = new CEMILData(CEMILData.MC_LDATA_IND, SRC, DST, SHORT_TPDU, Priority.LOW, true, 0);

		final CEMILData out = adjust(frame);

		assertNotNull(out, "upstream discards here; the fork deliberately does not -- see HOP-COUNT-PATCH.md");
		assertSame(frame, out);
		assertEquals(0, out.getHopCount(), "hop count 0 is forwarded as-is, not decremented");
	}

	/** Branch 3 -- an already-extended frame stays extended, and is rebuilt rather than mutated in place. */
	@Test
	void extendedFrameStaysExtendedAndIsDecremented()
	{
		final CEMILDataEx frame = new CEMILDataEx(CEMILData.MC_LDATA_IND, SRC, DST, SHORT_TPDU, Priority.LOW,
				true, 6);

		final CEMILData out = adjust(frame);

		assertInstanceOf(CEMILDataEx.class, out, "extended type must be preserved");
		assertEquals(5, out.getHopCount());
		assertEquals(6, frame.getHopCount(), "upstream mutated in place; the fork rebuilds, leaving the input "
				+ "untouched -- pinned because callers could come to rely on either");
		assertArrayEquals(SHORT_TPDU, out.getPayload());
		assertEquals(SRC, out.getSource());
		assertEquals(DST, out.getDestination());
		assertEquals(Priority.LOW, out.getPriority());
	}

	/**
	 * Branch 4 -- the {@code payload.length > 16} arm, which is <b>unreachable</b>.
	 * <p>
	 * That arm sits after the {@code instanceof CEMILDataEx} check, so it is only evaluated for a frame that
	 * is <i>not</i> already extended -- and a plain {@link CEMILData} cannot hold more than 16 payload bytes:
	 * its constructor rejects it outright ("maximum TPDU length is 16 in L-Data frames", CEMILData.java:234).
	 * So on that path {@code payload.length > 16} is always false and the {@code CEMILDataEx} arm of the
	 * ternary is dead code.
	 * <p>
	 * It is harmless, and it reads as a defensive guard written without realising the type system already
	 * guarantees the condition. It is pinned here so nobody deletes it as "obviously dead" without also
	 * noticing that the observable contract below -- long payload in, extended frame out -- is real and is
	 * satisfied by the earlier branch.
	 */
	@Test
	void longPayloadCannotReachTheStandardFrameRebuild()
	{
		final byte[] longTpdu = tpdu(17);

		assertThrows(KNXIllegalArgumentException.class,
				() -> new CEMILData(CEMILData.MC_LDATA_IND, SRC, DST, longTpdu, Priority.LOW, true, 6),
				"if this ever succeeds, the > 16 branch became reachable and needs real coverage");

		// the only way a long payload reaches this method at all is already-extended, which the earlier
		// branch handles -- so the observable contract still holds
		final CEMILDataEx frame = new CEMILDataEx(CEMILData.MC_LDATA_IND, SRC, DST, longTpdu, Priority.LOW,
				true, 6);
		final CEMILData out = adjust(frame);

		assertInstanceOf(CEMILDataEx.class, out, "a long payload must always come back extended");
		assertEquals(5, out.getHopCount());
		assertArrayEquals(longTpdu, out.getPayload(), "payload must survive the rebuild intact");
	}

	/** The boundary of branch 4: exactly 16 payload bytes still fits a standard frame. */
	@Test
	void payloadOfExactlySixteenStaysStandard()
	{
		final byte[] boundary = tpdu(16);
		final CEMILData frame = new CEMILData(CEMILData.MC_LDATA_IND, SRC, DST, boundary, Priority.LOW, true, 6);

		final CEMILData out = adjust(frame);

		assertEquals(CEMILData.class, out.getClass(), "16 bytes is not > 16, so this stays a standard frame");
		assertEquals(5, out.getHopCount());
		assertArrayEquals(boundary, out.getPayload());
	}

	/**
	 * The property the five call sites depend on being false: this method never returns {@code null}, which is
	 * why every {@code send == null} guard around it is unreachable. If a rebase restores upstream's discard,
	 * this test fails and those guards become live again -- which is the moment to re-read them.
	 */
	@Test
	void neverReturnsNullForAnyBranch()
	{
		assertNotNull(adjust(systemBroadcast(0)));
		assertNotNull(adjust(new CEMILData(CEMILData.MC_LDATA_IND, SRC, DST, SHORT_TPDU, Priority.LOW, true, 0)));
		assertNotNull(adjust(new CEMILData(CEMILData.MC_LDATA_IND, SRC, DST, SHORT_TPDU, Priority.LOW, true, 7)));
		assertNotNull(adjust(new CEMILDataEx(CEMILData.MC_LDATA_IND, SRC, DST, tpdu(20), Priority.LOW, true, 3)));
	}

	/** Hop counts in between are simply decremented by one. */
	@Test
	void ordinaryHopCountsAreDecrementedByOne()
	{
		for (int hop = 1; hop <= 7; hop++) {
			final CEMILData frame = new CEMILData(CEMILData.MC_LDATA_IND, SRC, DST, SHORT_TPDU, Priority.LOW,
					true, hop);
			assertEquals(hop - 1, adjust(frame).getHopCount(), "hop " + hop + " must decrement to " + (hop - 1));
		}
	}
}
