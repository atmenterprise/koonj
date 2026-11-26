#!/bin/sh
set -eu
if ! awslocal s3api head-bucket --bucket "$KOONJ_LOCAL_BUCKET" >/dev/null 2>&1; then
    if [ "$AWS_DEFAULT_REGION" = us-east-1 ]; then
        awslocal s3api create-bucket --bucket "$KOONJ_LOCAL_BUCKET"
    else
        awslocal s3api create-bucket --bucket "$KOONJ_LOCAL_BUCKET" --create-bucket-configuration "LocationConstraint=$AWS_DEFAULT_REGION"
    fi
fi
