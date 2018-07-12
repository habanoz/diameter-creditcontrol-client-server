package org.habanoz.diametertest.cc;

import org.jdiameter.api.*;
import org.jdiameter.server.impl.StackImpl;
import org.jdiameter.server.impl.helpers.XMLConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

public class DiameterCCServer implements NetworkReqListener {
    private static final Logger logger = LoggerFactory.getLogger(DiameterCCServer.class);

    private static final String configFile = "org/example/server/server-jdiameter-config.xml";

    // Defs for our app
    private static final int commandCode = 272;
    private static final long applicationID = 4;
    private ApplicationId authAppId = ApplicationId.createByAuthAppId(applicationID);

    // Constants
    // https://tools.ietf.org/html/rfc4006#section-8.3
    private static final int INITIAL_REQUEST = 1;
    private static final int UPDATE_REQUEST = 2;
    private static final int TERMINATION_REQUEST = 3;


    // instance variables
    private Stack stack;

    private void initStack() {
        logger.info("Initializing Stack...");

        InputStream is = null;
        try {

            this.stack = new StackImpl();

            is = this.getClass().getClassLoader().getResourceAsStream(configFile);

            Configuration config = new XMLConfiguration(is);
            SessionFactory factory = stack.init(config);

            logger.info("Stack Configuration successfully loaded. Factory {}",factory.toString());


            Set<org.jdiameter.api.ApplicationId> appIds = stack.getMetaData().getLocalPeer().getCommonApplications();

            logger.info("Diameter Stack  :: Supporting " + appIds.size() + " applications.");
            for (org.jdiameter.api.ApplicationId x : appIds) {
                logger.info("Diameter Stack  :: Common :: " + x);
            }
            is.close();
            Network network = stack.unwrap(Network.class);
            network.addNetworkReqListener(this, this.authAppId);
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


    public static void main(String[] args) {
        DiameterCCServer es = new DiameterCCServer();
        es.initStack();
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * org.jdiameter.api.NetworkReqListener#processRequest(org.jdiameter.api
     * .Request)
     */
    @Override
    public Answer processRequest(Request request) {
        if (request.getCommandCode() != commandCode) {
            logger.error("Received bad answer: " + request.getCommandCode());
            return null;
        }
        AvpSet requestAvpSet = request.getAvps();

        try {
            int requestType = requestAvpSet.getAvp(Avp.CC_REQUEST_TYPE).getInteger32();


            Answer answer = createAnswer(request, 2001);
            AvpSet answerAvpSet = answer.getAvps();

            switch (requestType) {
                case INITIAL_REQUEST:
                case UPDATE_REQUEST:
                    Avp requestAvpMSSC = requestAvpSet.getAvp(Avp.MULTIPLE_SERVICES_CREDIT_CONTROL);
                    Avp requestAvpMSSC_RU = requestAvpMSSC.getGrouped().getAvp(Avp.REQUESTED_SERVICE_UNIT);
                    int requestedUnits = requestAvpMSSC_RU.getGrouped().getAvp(Avp.CC_TIME).getInteger32();

                    createVoiceMSCC(answerAvpSet, requestType, requestedUnits);
                    break;
                case TERMINATION_REQUEST:
                default:
                    break;
            }

            return answer;
        } catch (AvpDataException | NullPointerException e) {
            logger.error("Error while processing request", e);
            return createAnswer(request, 5012);
        }
    }


    private void createVoiceMSCC(AvpSet avpSet, int requestType, int grantedUnits) {

        AvpSet creditControl = avpSet.addGroupedAvp(Avp.MULTIPLE_SERVICES_CREDIT_CONTROL);

        if (requestType == INITIAL_REQUEST || requestType == UPDATE_REQUEST) {
            AvpSet requestedServiceUnit = creditControl.addGroupedAvp(Avp.GRANTED_SERVICE_UNIT);
            requestedServiceUnit.addAvp(Avp.CC_TOTAL_OCTETS, grantedUnits, true, false, true);
        }
    }


    private Answer createAnswer(Request r, int resultCode) {
        Answer answer = r.createAnswer(resultCode);
        AvpSet answerAvps = answer.getAvps();

        //add origin, its required by duplicate detection
        answerAvps.addAvp(Avp.ORIGIN_HOST, stack.getMetaData().getLocalPeer().getUri().getFQDN(), true, false, true);
        answerAvps.addAvp(Avp.ORIGIN_REALM, stack.getMetaData().getLocalPeer().getRealmName(), true, false, true);
        return answer;
    }
}