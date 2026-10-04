#!/system/bin/sh
# Restore the hidden-API denylist when the user removes the module.
settings put global hidden_api_policy 0
settings delete global hidden_api_policy_pre_p_apps
settings delete global hidden_api_policy_p_apps
