package uk.gov.ons.ctp.integration.censusfieldsvc.service.impl;

import com.godaddy.logging.Logger;
import com.godaddy.logging.LoggerFactory;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import uk.gov.ons.ctp.common.domain.Channel;
import uk.gov.ons.ctp.common.domain.Language;
import uk.gov.ons.ctp.common.domain.Source;
import uk.gov.ons.ctp.common.error.CTPException;
import uk.gov.ons.ctp.integration.caseapiclient.caseservice.CaseServiceClientServiceImpl;
import uk.gov.ons.ctp.integration.caseapiclient.caseservice.model.CaseContainerDTO;
import uk.gov.ons.ctp.integration.caseapiclient.caseservice.model.QuestionnaireIdDTO;
import uk.gov.ons.ctp.integration.censusfieldsvc.config.AppConfig;
import uk.gov.ons.ctp.integration.censusfieldsvc.service.LauncherService;
import uk.gov.ons.ctp.integration.censusfieldsvc.service.impl.FieldServiceException.Fault;
import uk.gov.ons.ctp.integration.eqlaunch.service.EqLaunchData;
import uk.gov.ons.ctp.integration.eqlaunch.service.EqLaunchService;

@Service
public class LauncherServiceImpl implements LauncherService {

  private static final Logger log = LoggerFactory.getLogger(LauncherServiceImpl.class);

  @Autowired private AppConfig appConfig;

  @Autowired private CaseServiceClientServiceImpl caseServiceClient;

  @Autowired EqLaunchService eqLaunchService;

  @Override
  public LaunchDetails getEqUrl(String userId, UUID caseId) throws FieldServiceException {
    log.with("userId", userId).with("caseId", caseId).debug("Entering getEqUrl()");
    CaseContainerDTO caseDetails = null;
    QuestionnaireIdDTO questionnaireIdDto = null;

    try {
      log.debug("Fetching Case");
      caseDetails = caseServiceClient.getCaseById(caseId, false);
      log.with(caseDetails).debug("The case details received");

      log.debug("Fetching Questionnaire");
      questionnaireIdDto = caseServiceClient.getReusableQuestionnaireId(caseId);
      log.with(questionnaireIdDto).debug("The questionnaire id received");

      log.debug("Done fetching");

      if (!questionnaireIdDto.isActive()) {
        log.with("caseId", caseId)
            .with("questionnaireId", questionnaireIdDto.getQuestionnaireId())
            .info("Questionnaire is inactive");
        throw new FieldServiceException(Fault.QUESTIONNAIRE_INACTIVE);
      }
    } catch (ResponseStatusException fse) {
      Fault fault = null;
      switch (fse.getStatus()) {
        case NOT_FOUND:
          fault = Fault.RESOURCE_NOT_FOUND;
          break;
        case BAD_REQUEST:
          fault = Fault.BAD_REQUEST;
          break;
        case INTERNAL_SERVER_ERROR:
          fault = Fault.SYSTEM_ERROR;
          break;
        default:
          fault = Fault.SYSTEM_ERROR;
          break;
      }
      throw new FieldServiceException(fault);
    }

    caseDetails.setRegion(caseDetails.getOa());
    String accountServiceUrl = null;
    String accountServiceLogoutUrl = "https://" + appConfig.getDomain() + "/questionnaireSaved";

    String encryptedPayload = "";
    try {
      EqLaunchData eqLaunchData =
          EqLaunchData.builder()
              .language(Language.ENGLISH)
              .source(Source.FIELD_SERVICE)
              .channel(Channel.FIELD)
              .questionnaireId(questionnaireIdDto.getQuestionnaireId())
              .formType(questionnaireIdDto.getFormType())
              .salt(appConfig.getEq().getResponseIdSalt())
              .caseContainer(caseDetails)
              .userId(userId)
              .accountServiceUrl(accountServiceUrl)
              .accountServiceLogoutUrl(accountServiceLogoutUrl)
              .build();
      encryptedPayload = eqLaunchService.getEqLaunchJwe(eqLaunchData);
    } catch (CTPException e) {
      log.with("caseId", caseId)
          .with("questionnaireId", questionnaireIdDto.getQuestionnaireId())
          .error("Failed to create JWE payload for eq launch", e);
      throw new FieldServiceException(Fault.SYSTEM_ERROR);
    }

    String eqUrl = "https://" + appConfig.getEq().getHost() + "?token=" + encryptedPayload;
    log.with(eqUrl).debug("EQ URL");

    LaunchDetails launchDetails = new LaunchDetails();
    launchDetails.setEqUrl(eqUrl);
    launchDetails.setQuestionnaireId(questionnaireIdDto.getQuestionnaireId());
    launchDetails.setCaseId(caseDetails.getId());

    log.debug("Exiting getEqUrl()");
    return launchDetails;
  }
}
