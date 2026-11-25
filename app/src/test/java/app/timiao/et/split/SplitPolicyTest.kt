package app.timiao.et.split

import app.timiao.et.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPolicyTest {

    @Test
    fun decodeDefaultsForMissingOrInvalidText() {
        assertEquals(SplitPolicy(), SplitPolicyCodec.decode(null))
        assertEquals(SplitPolicy(), SplitPolicyCodec.decode(""))
        assertEquals(SplitPolicy(), SplitPolicyCodec.decode("garbage"))
        assertEquals(SplitPolicy(), SplitPolicyCodec.decode("v1\nbogus\ncom.a"))
    }

    @Test
    fun encodeDecodeRoundTripNormalizesAndSorts() {
        val policy = SplitPolicy(
            mode = SplitMode.Allow,
            packages = setOf("  com.b ", "com.a", "", "com.b"),
        )
        val encoded = SplitPolicyCodec.encode(policy)
        assertEquals("v1\nallow\ncom.a\ncom.b\n", encoded)
        assertEquals(
            SplitPolicy(SplitMode.Allow, setOf("com.a", "com.b")),
            SplitPolicyCodec.decode(encoded),
        )
    }

    @Test
    fun allModeDisallowsOnlySelf() {
        val plan = SplitPolicy(SplitMode.All, setOf("com.other")).toFilterPlan(SELF)
        assertEquals(emptySet<String>(), plan.allowed)
        assertEquals(setOf(SELF), plan.disallowed)
    }

    @Test
    fun allowModeKeepsOnlySelectedAppsAndRemovesSelf() {
        val plan = SplitPolicy(SplitMode.Allow, setOf("com.chrome", SELF))
            .toFilterPlan(SELF)
        assertEquals(setOf("com.chrome"), plan.allowed)
        assertEquals(emptySet<String>(), plan.disallowed)
    }

    @Test
    fun allowModeWithOnlySelfFallsBackToExcludingSelf() {
        val plan = SplitPolicy(SplitMode.Allow, setOf(SELF)).toFilterPlan(SELF)
        assertEquals(emptySet<String>(), plan.allowed)
        assertEquals(setOf(SELF), plan.disallowed)
    }

    @Test
    fun allowModeWithEmptySelectionFallsBackToExcludingSelf() {
        val plan = SplitPolicy(SplitMode.Allow).toFilterPlan(SELF)
        assertEquals(emptySet<String>(), plan.allowed)
        assertEquals(setOf(SELF), plan.disallowed)
    }

    @Test
    fun denyModeAlwaysAddsSelf() {
        val plan = SplitPolicy(SplitMode.Deny, setOf("com.chrome", SELF))
            .toFilterPlan(SELF)
        assertEquals(emptySet<String>(), plan.allowed)
        assertEquals(setOf("com.chrome", SELF), plan.disallowed)
    }

    @Test
    fun plansNeverUseBothAllowedAndDisallowed() {
        val modes = SplitMode.entries
        val selections = listOf(emptySet(), setOf(SELF), setOf("com.chrome"), setOf(SELF, "com.chrome"))
        for (mode in modes) {
            for (selection in selections) {
                val plan = SplitPolicy(mode, selection).toFilterPlan(SELF)
                assertFalse(
                    "$mode $selection uses both lists",
                    plan.allowed.isNotEmpty() && plan.disallowed.isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun modeLabelsAreResourceBacked() {
        assertEquals(R.string.split_mode_all, SplitMode.All.labelRes)
        assertEquals(R.string.split_mode_allow, SplitMode.Allow.labelRes)
        assertEquals(R.string.split_mode_deny, SplitMode.Deny.labelRes)
    }

    private companion object {
        const val SELF = "app.timiao.et"
    }
}
