package com.example

import com.example.data.repository.LicenseService
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanyCodeTest {
    @Test
    fun typedCodesMatchTheStoredCode() {
        assertEquals("PATHAO7", LicenseService.normalizeCompanyCode("pathao7"))
        assertEquals("PATHAO7", LicenseService.normalizeCompanyCode(" Pathao 7 "))
        assertEquals("PATHAO7", LicenseService.normalizeCompanyCode("pa-thao.7"))
    }

    @Test
    fun emptyMeansNoCompany() {
        assertEquals("", LicenseService.normalizeCompanyCode(""))
        assertEquals("", LicenseService.normalizeCompanyCode("  - "))
    }
}
