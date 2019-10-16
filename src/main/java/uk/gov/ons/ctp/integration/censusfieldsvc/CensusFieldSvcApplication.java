package uk.gov.ons.ctp.integration.censusfieldsvc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;
import org.opensaml.saml2.metadata.provider.ResourceBackedMetadataProvider;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.client.RestTemplate;
import com.github.ulisesbocchio.spring.boot.security.saml.configurer.ServiceProviderBuilder;
import com.github.ulisesbocchio.spring.boot.security.saml.configurer.ServiceProviderConfigurerAdapter;
import com.godaddy.logging.LoggingConfigs;
import com.rabbitmq.client.RpcClient;
import uk.gov.ons.ctp.common.error.RestExceptionHandler;
import uk.gov.ons.ctp.common.event.EventPublisher;
import uk.gov.ons.ctp.common.event.EventSender;
import uk.gov.ons.ctp.common.event.SpringRabbitEventSender;
import uk.gov.ons.ctp.common.jackson.CustomObjectMapper;
import uk.gov.ons.ctp.integration.censusfieldsvc.config.AppConfig;
import uk.gov.ons.ctp.integration.censusfieldsvc.config.ReverseProxyConfig;
import uk.gov.ons.ctp.integration.censusfieldsvc.config.SsoConfig;

/** The 'main' entry point for the CensusField Svc SpringBoot Application. */
@SpringBootApplication
@EnableSAMLSSOWhenNotInTest
@IntegrationComponentScan("uk.gov.ons.ctp.integration")
@ComponentScan(basePackages = {"uk.gov.ons.ctp.integration"})
@ImportResource("springintegration/main.xml")
@EnableCaching
public class CensusFieldSvcApplication {
//  private static final String PROPERTY_
  
  private AppConfig appConfig;

  @Value("${queueconfig.event-exchange}")
  private String eventExchange;

  
  // Table to convert from AddressIndex response status values to values that can be returned to the
  // invoker of this service
  private static final HashMap<HttpStatus, HttpStatus> httpErrorMapping;

  static {
    httpErrorMapping = new HashMap<HttpStatus, HttpStatus>();
    httpErrorMapping.put(HttpStatus.OK, HttpStatus.OK);
    httpErrorMapping.put(HttpStatus.BAD_REQUEST, HttpStatus.INTERNAL_SERVER_ERROR);
    httpErrorMapping.put(HttpStatus.UNAUTHORIZED, HttpStatus.INTERNAL_SERVER_ERROR);
    httpErrorMapping.put(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND);
    httpErrorMapping.put(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.INTERNAL_SERVER_ERROR);
    httpErrorMapping.put(HttpStatus.GATEWAY_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR);
    httpErrorMapping.put(HttpStatus.REQUEST_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR);
  }

  // This is the http status to be used for error mapping if a status is not in the mapping table
  HttpStatus defaultHttpStatus = HttpStatus.INTERNAL_SERVER_ERROR;

  /**
   * Constructor for CensusFieldSvcApplication
   *
   * @param appConfig contains the configuration for the current deployment.
   */
  @Autowired
  public CensusFieldSvcApplication(final AppConfig appConfig) {
    this.appConfig = appConfig;
  }

  /**
   * The main entry point for this application.
   *
   * @param args runtime command line args
   */
  public static void main(final String[] args) {

    SpringApplication.run(CensusFieldSvcApplication.class, args);
  }

  /**
   * The restTemplate bean injected in REST client classes
   *
   * @return the restTemplate used in REST calls
   */
  @Bean
  public RestTemplate restTemplate() {
    return new RestTemplate();
  }

  /**
   * Custom Object Mapper
   *
   * @return a customer object mapper
   */
  @Bean
  @Primary
  public CustomObjectMapper customObjectMapper() {
    return new CustomObjectMapper();
  }

  /**
   * Bean used to map exceptions for endpoints
   *
   * @return the service client
   */
  @Bean
  public RestExceptionHandler restExceptionHandler() {
    return new RestExceptionHandler();
  }

  /**
   * Bean used to publish asynchronous event messages
   *
   * @param connectionFactory RabbitMQ connection settings and strategies
   * @return the event publisher
   */
  @Bean
  public EventPublisher eventPublisher(final ConnectionFactory connectionFactory) {
    final var template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(new Jackson2JsonMessageConverter());
    template.setExchange("events");
    template.setChannelTransacted(true);

    EventSender sender = new SpringRabbitEventSender(template);
    return new EventPublisher(sender);
  }

  @Value("#{new Boolean('${logging.useJson}')}")
  private boolean useJsonLogging;

  @PostConstruct
  public void initJsonLogging() {
    if (useJsonLogging) {
      LoggingConfigs.setCurrent(LoggingConfigs.getCurrent().useJson());
    }
  }

  @Configuration
  public static class MyServiceProviderConfig extends ServiceProviderConfigurerAdapter {
    @Autowired private AppConfig appConfig;

    @Value("${sso.useReverseProxy}")
    private boolean useReverseProxy;

    @Override
    public void configure(HttpSecurity http) throws Exception {
      http.authorizeRequests()
          .regexMatchers("/")
          .permitAll()
          .antMatchers("/completed")
          .permitAll()
          .antMatchers("/info")
          .permitAll()
          .antMatchers("/hello3")
          .permitAll()
          .regexMatchers("/anon/hello")
          .permitAll();
    }

    @Override
    public void configure(ServiceProviderBuilder serviceProvider) throws Exception {
      String idpMetadata = loadIdpMetadata();
      ResourceBackedMetadataProvider idpMetadataProvider = new ResourceBackedMetadataProvider(null, new StringResource(idpMetadata));
      
      serviceProvider
          .metadataGenerator()
          .entityId("localhost")
          .and()
          .sso()
          .and()
          .logout()
          .defaultTargetURL("/afterlogout")
          .and()
          .metadataManager()
          .metadataProvider(idpMetadataProvider)
          .defaultIDP("https://accounts.google.com/o/saml2?idpid=C00n4re6c")
          .refreshCheckInterval(60*1000)
          .and()
          .extendedMetadata()
          .idpDiscoveryEnabled(false) // disable IDP selection page
          .and()
          .keyManager()
          .privateKeyDERLocation("classpath:/localhost.key.der")
          .publicKeyPEMLocation("classpath:/localhost.cert");
      
      if (useReverseProxy) {
        ReverseProxyConfig reverseProxyConfig = appConfig.getSso().getReverseProxy();
        
        serviceProvider
               .samlContextProviderLb()
               .scheme(reverseProxyConfig.getScheme())
               .contextPath(reverseProxyConfig.getContextPath())
               .serverName(reverseProxyConfig.getServerName())
               .serverPort(reverseProxyConfig.getServerPort())
               .includeServerPortInRequestURL(reverseProxyConfig.isIncludeServerPortInRequestURL());
      }
    }

    private String loadIdpMetadata() throws IOException {
      String rawIdpMetadata = readResourceFile("IDPMetadata.xml");
      
      String idpMetadata = replacePlaceholders(rawIdpMetadata);
      return idpMetadata;
    }
    
    private String readResourceFile(String resourcePath) throws IOException {
      try (InputStream inputStream =
          getClass().getClassLoader().getResource(resourcePath).openStream()) {
        StringBuilder textBuilder = new StringBuilder();
        try (Reader reader = new BufferedReader(new InputStreamReader
          (inputStream, Charset.forName(StandardCharsets.UTF_8.name())))) {
            int c = 0;
            while ((c = reader.read()) != -1) {
                textBuilder.append((char) c);
            }
        }
        String idpMetadata = textBuilder.toString();
        return idpMetadata;
      }
    }

    /**
     * Replaces placeholders in the supplied string with actual values from system properties.
     * Placeholders are in the form '${name}'. 
     * 
     * @param idpMetadata is the string which requires placeholders to be resolved.
     * @return the updated String.
     * @throws IllegalStateException if there is no system property for a named placeholder.
     */
    private String replacePlaceholders(String idpMetadata) {
      String updatedIdpMetadata = idpMetadata;
      
      // Find names of all placeholders, from markers such as '${idpId}'
      LinkedHashSet<String> placeholderNames = new LinkedHashSet<String>();
      Pattern placeholderPattern = Pattern.compile("\\$\\{(.*)\\}");
      Matcher matcher = placeholderPattern.matcher(idpMetadata);
      while (matcher.find()) {
        String placeholderName = matcher.group(1);
        placeholderNames.add(placeholderName);
      }
      
      // Replace all placeholders with actual value from system properties
      for (String placeholderName : placeholderNames) {
        String placeholderValue = System.getProperty(placeholderName);
        if (placeholderValue == null) {
          throw new IllegalStateException("No system property for metadata placeholder '" + placeholderName + "'");
        }
        
        String placeholderSpec = "\\$\\{" + placeholderName + "\\}";
        updatedIdpMetadata = updatedIdpMetadata.replaceAll(placeholderSpec, placeholderValue);
      }
      
      return updatedIdpMetadata;
    }
  }
  
//  private int getIntSystemProperty(String propertyName) {
//    String propertyValueAsString = getSystemProperty(propertyName);
//    
//    try {
//      int propertyValue = Integer.parseInt(propertyValueAsString);
//      return propertyValue;
//    } catch (NumberFormatException e) {
//      throw new IllegalStateException("Failed to parse integer property. Name is '" + propertyName + "' with value '" + propertyValueAsString + "'");
//    }
//  }
//  
//  private int getIntSystemProperty(String propertyName) {
//    String propertyValueAsString = getSystemProperty(propertyName);
//    
//    int propertyValue = Integer.parseInt(propertyValueAsString);
//    return propertyValue;
//  }
//  
//  private String getSystemProperty(String propertyName) {
//    String propertyValue = System.getProperty(propertyName);
//    if (propertyValue == null) {
//      throw new IllegalStateException("No system property defined for '" + propertyName + "'");
//    }
//    
//    return propertyValue;
//  }
}
