package org.habanoz.diametertest.cc;

public final class DiameterConfiguration {
    private String	originHost;
    private String	originRealm;
    private String	destinationHost;
    private String	destinationRealm;
    private String msisdn;
    private String target_msisdn;
    private String imsi;

    public DiameterConfiguration(String originHost, String originRealm, String destinationHost, String destinationRealm, String msisdn, String target_msisdn, String imsi) {
        this.originHost = originHost;
        this.originRealm = originRealm;
        this.destinationHost = destinationHost;
        this.destinationRealm = destinationRealm;
        this.msisdn = msisdn;
        this.target_msisdn = target_msisdn;
        this.imsi = imsi;
    }

    public String getMsisdn() {
        return msisdn;
    }

    public String getTarget_msisdn() {
        return target_msisdn;
    }

    public String getImsi() {
        return imsi;
    }

    public String getOriginHost() {
        return originHost;
    }

    public String getOriginRealm() {
        return originRealm;
    }

    public String getDestinationHost() {
        return destinationHost;
    }

    public String getDestinationRealm() {
        return destinationRealm;
    }
}