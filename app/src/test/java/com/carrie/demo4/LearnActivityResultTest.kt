package com.carrie.demo4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 纯 Kotlin 单元测试：验证 Activity Result 的 RequestCode 及数据 Key 映射。
 */
class LearnActivityResultTest {

    @Test
    fun testRequestCodesAreUnique() {
        assertNotEquals(
            "Request codes for A->B and B->C must be distinct",
            LearnAActivity.REQUEST_CODE_A_TO_B,
            LearnBActivity.REQUEST_CODE_B_TO_C
        )
        assertEquals(1001, LearnAActivity.REQUEST_CODE_A_TO_B)
        assertEquals(2002, LearnBActivity.REQUEST_CODE_B_TO_C)
    }

    @Test
    fun testIntentExtraKeys() {
        assertEquals("extra_request_code", LearnAActivity.EXTRA_REQUEST_CODE)
        assertEquals("extra_request_msg", LearnAActivity.EXTRA_REQUEST_MSG)
        assertEquals("extra_result_data", LearnAActivity.EXTRA_RESULT_DATA)
    }
}
