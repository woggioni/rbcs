package net.woggioni.gbcs.test;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.util.Objects;

public class X500NameTest {

    @Test
    @SneakyThrows
    void test() {
        final var name =
                "C=SG, L=Bugis, CN=woggioni@f6aa5663ef26, emailAddress=oggioni.walter@gmail.com, street=1 Fraser Street\\, Duo Residences #23-05, postalCode=189350, GN=Walter, SN=Oggioni, pseudonym=woggioni";
        final var ldapName = new LdapName(name);
        final var value = ldapName.getRdns()
                .stream()
                .filter(it -> Objects.equals("CN", it.getType()))
                .findFirst()
                .map(Rdn::getValue)
                .orElseThrow(Assertions::fail);
        Assertions.assertEquals("woggioni@f6aa5663ef26", value);
    }
}

