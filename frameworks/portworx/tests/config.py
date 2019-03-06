import os
import logging
import textwrap
import traceback

import sdk_hosts
import sdk_jobs
import sdk_plan
import sdk_utils
import string
import random



def get_foldered_service_name():
    return sdk_utils.get_foldered_name(SERVICE_NAME)

def get_random_string(char_count = 8):
    return ''.join([random.choice(string.ascii_letters + string.digits) for n in range(char_count)])

# Portworx service specific configurations
PACKAGE_NAME = 'portworx'
SERVICE_NAME = 'portworx'
PX_AGENT_USER = "vagrant"
PX_CLEANUP_SCRIPT_PATH = 'frameworks/portworx/scripts/px_dcos_cleanup.sh'
PX_TIMEOUT = 15 * 60 # 15 minutes timeout for portworx operations.

DEFAULT_TASK_COUNT = 1

PX_IMAGE = os.environ['PX_IMAGE']
PX_KVDB_SERVER = os.environ['KVDB']
PX_OPTIONS = "-a -x mesos -d enp0s8 -m enp0s8"
PX_CLUSTER_NAME = "portworx-dcos-" + get_random_string(16) 

PX_NODE_OPTIONS = { "node": { "portworx_options": PX_OPTIONS,
                            "kvdb_servers": PX_KVDB_SERVER,
                            "internal_kvdb": False,
                            "count": DEFAULT_TASK_COUNT,
                            "portworx_image": PX_IMAGE,
                            "portworx_cluster": PX_CLUSTER_NAME,
                  } }

PX_SEC_OPTIONS = { "group_name": "px_service_grp",
                   "user_name": "px_user_1",
                   "user_password": "px_user_1_password",
                   "base_path": "pwx/secrets",
                   "secret_value": "px_secret_value",
                   "secret_key": "px_secret_key",
                   "user_secret_id": "user_secrets",
                   "password_secret_id": "password_secrets",
                   "encrypted_volume_name" : "px_encrypt_vol_1"
                 }
