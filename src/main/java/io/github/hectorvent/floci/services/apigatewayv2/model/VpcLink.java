package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcLink {
    private String vpcLinkId;
    private String name;
    private String vpcLinkStatus;
    private List<String> securityGroupIds = new ArrayList<>();
    private List<String> subnetIds = new ArrayList<>();
    private Map<String, String> tags = new HashMap<>();

    public VpcLink() {}

    public String getVpcLinkId() { return vpcLinkId; }
    public void setVpcLinkId(String vpcLinkId) { this.vpcLinkId = vpcLinkId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVpcLinkStatus() { return vpcLinkStatus; }
    public void setVpcLinkStatus(String vpcLinkStatus) { this.vpcLinkStatus = vpcLinkStatus; }

    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> securityGroupIds) { this.securityGroupIds = securityGroupIds; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
