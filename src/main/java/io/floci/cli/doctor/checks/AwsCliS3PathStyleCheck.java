package io.floci.cli.doctor.checks;

import io.floci.cli.doctor.Check;
import io.floci.cli.doctor.CheckResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AwsCliS3PathStyleCheck implements Check {

    @Override
    public String name() {
        return "aws.cli.s3.pathstyle";
    }

    @Override
    public CheckResult run(String endpoint, String container) {
        Path awsConfig = Path.of(System.getProperty("user.home"), ".aws", "config");
        if (!Files.exists(awsConfig)) {
            return CheckResult.ok("aws.cli.s3.pathstyle", "~/.aws/config not found — skipped");
        }
        try {
            String content = Files.readString(awsConfig);
            if (content.contains("addressing_style") && content.contains("path")) {
                return CheckResult.ok("aws.cli.s3.pathstyle", "S3 path-style addressing configured");
            }
            return CheckResult.warn("aws.cli.s3.pathstyle",
                    "~/.aws/config missing 's3.addressing_style = path' — virtual-hosted S3 requests may fail",
                    "Add to ~/.aws/config:\n" +
                    "  [default]\n" +
                    "  s3 =\n" +
                    "    addressing_style = path");
        } catch (IOException e) {
            return CheckResult.warn("aws.cli.s3.pathstyle", "Could not read ~/.aws/config", null);
        }
    }
}
