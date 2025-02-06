package net.woggioni.rbcs.server.test

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import javax.naming.ldap.LdapName

class X500NameTest {

    @Test
    fun test() {
        val name =
            "C=SG, L=Bugis, CN=woggioni@f6aa5663ef26, emailAddress=oggioni.walter@gmail.com, street=1 Fraser Street\\, Duo Residences #23-05, postalCode=189350, GN=Walter, SN=Oggioni, pseudonym=woggioni"
        val ldapName = LdapName(name)
        val value = ldapName.rdns.asSequence().find {
            it.type == "CN"
        }!!.value
        Assertions.assertEquals("woggioni@f6aa5663ef26", value)
    }
}