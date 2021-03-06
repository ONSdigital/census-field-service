package uk.gov.ons.ctp.integration.censusfieldsvc;

import com.github.ulisesbocchio.spring.boot.security.saml.annotation.EnableSAMLSSO;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;

@ConditionalOnMissingClass("org.junit.Test")
@EnableSAMLSSO
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableSAMLSSOWhenNotInTest {}
