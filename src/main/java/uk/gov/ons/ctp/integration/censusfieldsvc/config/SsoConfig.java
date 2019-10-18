package uk.gov.ons.ctp.integration.censusfieldsvc.config;

import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SsoConfig {
  private boolean useReverseProxy;
  @NotBlank private String idpId;
  @NotBlank private String metadataCertificate;
  private ReverseProxyConfig reverseProxy;
}