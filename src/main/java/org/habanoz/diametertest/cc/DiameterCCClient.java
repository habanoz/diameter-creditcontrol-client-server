package org.habanoz.diametertest.cc;

import org.jdiameter.api.*;
import org.jdiameter.server.impl.StackImpl;
import org.jdiameter.server.impl.helpers.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Properties;
import java.util.Set;

public class DiameterCCClient implements EventListener<Request, Answer> {
	private static final String configFile = "org/example/client/client-jdiameter-config.xml";
	private static final Logger logger = LoggerFactory.getLogger(DiameterCCClient.class);
	private static final String appConfigFile = "org/example/client/application.properties";
	private static final String VOICE_SERVICE_CONTEXT_ID = "32260@3gpp.org";

	// Constants
	// https://tools.ietf.org/html/rfc4006#section-8.3
	private static final int INITIAL_REQUEST = 1;
	private static final int UPDATE_REQUEST = 2;
	private static final int TERMINATION_REQUEST = 3;

	// https://www.iana.org/assignments/aaa-parameters/aaa-parameters.xhtml#aaa-parameters-46
	private static final long applicationID = 4;
	private ApplicationId authAppId = ApplicationId.createByAuthAppId(applicationID);
	// https://en.wikipedia.org/wiki/Diameter_Credit-Control_Application
	private static final int commandCode = 272; // Credit-Control Messages

	private static final int REQUESTED_TIME = 10;

	private static final long VENDOR_ID = 10415; //3GPP
	private static final int NODE_FUNCTIONALITY = 0;

	enum END_USER {
		E164(0), IMSI(1);

		int val;

		END_USER(int val) {
			this.val = val;
		}

	}

	public DiameterCCClient(int numberOfUpdates) {
		this.numberOfUpdates = numberOfUpdates;
	}

	// class variables
	private static DiameterConfiguration diameter_config;

	// instance variables
	private Stack stack;
	private SessionFactory factory;
	private Session session;
	private int currentState = -1;
	private int numberOfUpdates = 1;
	private int requestNumber = 0;

	public static void main(String[] args) {
		diameter_config = parseConfig();

		int numberOfUpdates = 1;
		if (args.length > 0)
			numberOfUpdates = Integer.parseInt(args[0]);
		logger.info("Number of updates to sent is {}", numberOfUpdates);
		DiameterCCClient dc = new DiameterCCClient(numberOfUpdates);
		dc.initStack();
		dc.start();
	}

	private static DiameterConfiguration parseConfig() {
		InputStream is = DiameterCCClient.class.getClassLoader().getResourceAsStream(appConfigFile);
		Properties props = new Properties();
		try {
			props.load(is);

			return new DiameterConfiguration(props.getProperty("origin.host"), props.getProperty("origin.realm"),
					props.getProperty("destination.host"), props.getProperty("destination.realm"), props.getProperty("msisdn"),
					props.getProperty("target.msisdn"), props.getProperty("imsi"));
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	private void initStack() {

		logger.info("Initializing Stack...");

		InputStream is = null;
		try {
			this.stack = new StackImpl();
			//Parse stack configuration
			is = this.getClass().getClassLoader().getResourceAsStream(configFile);
			Configuration config = new XMLConfiguration(is);
			factory = stack.init(config);
			logger.info("Stack Configuration successfully loaded.");

			//Print info about application
			Set<ApplicationId> appIds = stack.getMetaData().getLocalPeer().getCommonApplications();

			logger.info("Diameter Stack  :: Supporting " + appIds.size() + " applications.");
			for (org.jdiameter.api.ApplicationId x : appIds) {
				logger.info("Diameter Stack  :: Common :: " + x);
			}

			is.close();
			//Register network request listener, even though we wont receive requests
			//this has to be done to inform stack that we support application
			Network network = stack.unwrap(Network.class);
			network.addNetworkReqListener(request -> null, this.authAppId); //passing our example app id.

		} catch (Exception e) {
			e.printStackTrace();
			if (this.stack != null) {
				this.stack.destroy();
			}

			if (is != null) {
				try {
					is.close();
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			}
			return;
		}

		MetaData metaData = stack.getMetaData();
		if (metaData.getStackType() != StackType.TYPE_SERVER || metaData.getMinorVersion() <= 0) {
			stack.destroy();
			logger.error("Incorrect driver");
			return;
		}

		try {
			logger.info("Starting stack");
			stack.start();
			logger.info("Stack is running.");

		} catch (Exception e) {
			e.printStackTrace();
			stack.destroy();
			return;
		}

		logger.info("Stack initialization successfully completed.");
	}


	private void start() {
		try {
			//wait for connection to peer
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			String sessionId = VOICE_SERVICE_CONTEXT_ID + "-" + diameter_config.getImsi() + "-" + new Date().getTime();
			//do send
			this.session = this.factory.getNewSession(sessionId);
			logger.info("Sending initial request");
			sendNextRequest(INITIAL_REQUEST);
		} catch (InternalException | OverloadException | RouteException | IllegalDiameterStateException e) {
			e.printStackTrace();
		}

	}

	private void sendNextRequest(int requestType) throws InternalException, IllegalDiameterStateException, RouteException, OverloadException {
		Request r = compose(requestType);
		this.session.send(r, this);
	}

	private Request compose(int requestType) {
		Request requst = generateRequest(requestType);
		AvpSet avpSet = requst.getAvps();

		avpSet.addAvp(Avp.SERVICE_CONTEXT_ID, VOICE_SERVICE_CONTEXT_ID, true, false, true);

		createIMSInformation(avpSet, requestType);
		createVoiceMSCC(avpSet, requestType);

		return requst;
	}

	private static void createIMSInformation(AvpSet avpSet, int requestType) {
		AvpSet serviceInformation = avpSet.addGroupedAvp(Avp.SERVICE_INFORMATION, VENDOR_ID, true, false);
		AvpSet imsInformation = serviceInformation.addGroupedAvp(Avp.IMS_INFORMATION, VENDOR_ID, true, false);

		AvpSet eventType = imsInformation.addGroupedAvp(Avp.EVENT_TYPE, VENDOR_ID, true, false);
		eventType.addAvp(Avp.EVENT, requestType, VENDOR_ID, true, false, true);
		imsInformation.addAvp(Avp.NODE_FUNCTIONALITY, NODE_FUNCTIONALITY, VENDOR_ID, true, false, true);
		imsInformation.addAvp(Avp.CALLING_PARTY_ADDRESS, diameter_config.getMsisdn(), VENDOR_ID, true, false, true);
		imsInformation.addAvp(Avp.CALLED_PARTY_ADDRESS, diameter_config.getTarget_msisdn(), VENDOR_ID, true, false, true);
	}


	private Request generateRequest(int requestType) {
		Request request = session.createRequest(commandCode, authAppId, diameter_config.getDestinationRealm(), diameter_config.getDestinationHost());
		request.setRequest(true);

		AvpSet avpSet = request.getAvps();
		avpSet.addAvp(Avp.CC_REQUEST_TYPE, requestType);
		avpSet.addAvp(Avp.CC_REQUEST_NUMBER, requestNumber++);
		avpSet.addAvp(Avp.EVENT_TIMESTAMP, new Date());

		AvpSet subsriptionIdMSISDN = avpSet.addGroupedAvp(Avp.SUBSCRIPTION_ID);
		subsriptionIdMSISDN.addAvp(Avp.SUBSCRIPTION_ID_TYPE, END_USER.E164.val);
		subsriptionIdMSISDN.addAvp(Avp.SUBSCRIPTION_ID_DATA, diameter_config.getMsisdn(), true);

		AvpSet subsriptionIdIMSI = avpSet.addGroupedAvp(Avp.SUBSCRIPTION_ID);
		subsriptionIdIMSI.addAvp(Avp.SUBSCRIPTION_ID_TYPE, END_USER.IMSI.val);
		subsriptionIdIMSI.addAvp(Avp.SUBSCRIPTION_ID_DATA, diameter_config.getImsi(), true);

		return request;
	}

	private void createVoiceMSCC(AvpSet avpSet, int requestType) {
		AvpSet creditControl = avpSet.addGroupedAvp(Avp.MULTIPLE_SERVICES_CREDIT_CONTROL);

		if (requestType == INITIAL_REQUEST || requestType == UPDATE_REQUEST) {
			AvpSet requestedServiceUnit = creditControl.addGroupedAvp(Avp.REQUESTED_SERVICE_UNIT);
			requestedServiceUnit.addAvp(Avp.CC_TIME, REQUESTED_TIME, true, false, true);
		}
	}

	@Override
	public void receivedSuccessMessage(Request request, Answer answer) {

		if (answer.getCommandCode() != commandCode) {
			logger.error("Received bad answer: " + answer.getCommandCode());
			return;
		}

		//AvpSet answerAvpSet = answer.getAvps();
		Avp resultAvp = answer.getResultCode();

		try {
			if (resultAvp.getUnsigned32() != 2001) {
				this.session.release();
				this.session = null;
				logger.error("Something wrong happened at server side! Result code is {}", resultAvp.getUnsigned32());

				this.stack.destroy();
				System.exit(1);
				return;
			}

			switch (currentState) {
				case -1:
					logger.info("Initial state, sending update");
					sendNextRequest(UPDATE_REQUEST);
					currentState = INITIAL_REQUEST;
					break;
				case INITIAL_REQUEST:
					if (requestNumber - 1 > numberOfUpdates) {
						logger.info("update response reached, sending update {}", requestNumber - 1);
						sendNextRequest(UPDATE_REQUEST);
						currentState = UPDATE_REQUEST;
					} else {
						logger.info("update response reached, terminating");
						sendNextRequest(TERMINATION_REQUEST);
						currentState = UPDATE_REQUEST;
					}


					break;
				case UPDATE_REQUEST:
					logger.info("Terminate reached, disconnecting");
					this.session.release();
					this.session = null;
					logger.info("Disconnected");

					this.stack.destroy();
					System.exit(0);
					break;
				default:
					logger.error("Unexpected state value {}", currentState);
					break;
			}
		} catch (AvpDataException | InternalException | OverloadException | RouteException | IllegalDiameterStateException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void timeoutExpired(Request request) {
		logger.info("Timeout: session-id {}; application-id {}", request.getSessionId(), request.getApplicationId());
	}
}
