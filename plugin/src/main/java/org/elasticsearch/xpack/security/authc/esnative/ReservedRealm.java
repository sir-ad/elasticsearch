/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.esnative;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.settings.SecureSetting;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.SecurityLifecycleService;
import org.elasticsearch.xpack.security.authc.AuthenticationResult;
import org.elasticsearch.xpack.security.authc.RealmConfig;
import org.elasticsearch.xpack.security.authc.esnative.NativeUsersStore.ReservedUserInfo;
import org.elasticsearch.xpack.security.authc.support.CachingUsernamePasswordRealm;
import org.elasticsearch.xpack.security.authc.support.Hasher;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.security.support.Exceptions;
import org.elasticsearch.xpack.security.user.AnonymousUser;
import org.elasticsearch.xpack.security.user.ElasticUser;
import org.elasticsearch.xpack.security.user.KibanaUser;
import org.elasticsearch.xpack.security.user.LogstashSystemUser;
import org.elasticsearch.xpack.security.user.User;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A realm for predefined users. These users can only be modified in terms of changing their passwords; no other modifications are allowed.
 * This realm is <em>always</em> enabled.
 */
public class ReservedRealm extends CachingUsernamePasswordRealm {

    public static final String TYPE = "reserved";

    private final ReservedUserInfo bootstrapUserInfo;
    static final char[] EMPTY_PASSWORD_HASH = Hasher.BCRYPT.hash(new SecureString("".toCharArray()));
    static final ReservedUserInfo DISABLED_DEFAULT_USER_INFO = new ReservedUserInfo(EMPTY_PASSWORD_HASH, false, true);
    static final ReservedUserInfo ENABLED_DEFAULT_USER_INFO = new ReservedUserInfo(EMPTY_PASSWORD_HASH, true, true);

    public static final Setting<Boolean> ACCEPT_DEFAULT_PASSWORD_SETTING = Setting.boolSetting(
            Security.setting("authc.accept_default_password"), true, Setting.Property.NodeScope, Setting.Property.Filtered,
            Setting.Property.Deprecated);
    public static final Setting<SecureString> BOOTSTRAP_ELASTIC_PASSWORD = SecureSetting.secureString("bootstrap.password", null);

    private final NativeUsersStore nativeUsersStore;
    private final AnonymousUser anonymousUser;
    private final boolean realmEnabled;
    private final boolean anonymousEnabled;
    private final SecurityLifecycleService securityLifecycleService;

    public ReservedRealm(Environment env, Settings settings, NativeUsersStore nativeUsersStore, AnonymousUser anonymousUser,
                         SecurityLifecycleService securityLifecycleService, ThreadContext threadContext) {
        super(TYPE, new RealmConfig(TYPE, Settings.EMPTY, settings, env, threadContext));
        this.nativeUsersStore = nativeUsersStore;
        this.realmEnabled = XPackSettings.RESERVED_REALM_ENABLED_SETTING.get(settings);
        this.anonymousUser = anonymousUser;
        this.anonymousEnabled = AnonymousUser.isAnonymousEnabled(settings);
        this.securityLifecycleService = securityLifecycleService;
        final char[] hash = BOOTSTRAP_ELASTIC_PASSWORD.get(settings).length() == 0 ? EMPTY_PASSWORD_HASH :
                Hasher.BCRYPT.hash(BOOTSTRAP_ELASTIC_PASSWORD.get(settings));
        bootstrapUserInfo = new ReservedUserInfo(hash, true, hash == EMPTY_PASSWORD_HASH);
    }

    @Override
    protected void doAuthenticate(UsernamePasswordToken token, ActionListener<AuthenticationResult> listener) {
        if (realmEnabled == false) {
            listener.onResponse(AuthenticationResult.notHandled());
        } else if (isReserved(token.principal(), config.globalSettings()) == false) {
            listener.onResponse(AuthenticationResult.notHandled());
        } else {
            getUserInfo(token.principal(), ActionListener.wrap((userInfo) -> {
                AuthenticationResult result;
                if (userInfo != null) {
                    try {
                        if (userInfo.hasEmptyPassword) {
                            result = AuthenticationResult.terminate("failed to authenticate user [" + token.principal() + "]", null);
                        } else if (Hasher.BCRYPT.verify(token.credentials(), userInfo.passwordHash)) {
                            final User user = getUser(token.principal(), userInfo);
                            result = AuthenticationResult.success(user);
                        } else {
                            result = AuthenticationResult.terminate("failed to authenticate user [" + token.principal() + "]", null);
                        }
                    } finally {
                        assert userInfo.passwordHash != DISABLED_DEFAULT_USER_INFO.passwordHash : "default user info must be cloned";
                        assert userInfo.passwordHash != bootstrapUserInfo.passwordHash : "bootstrap user info must be cloned";
                        Arrays.fill(userInfo.passwordHash, (char) 0);
                    }
                } else {
                    result = AuthenticationResult.terminate("failed to authenticate user [" + token.principal() + "]", null);
                }
                // we want the finally block to clear out the chars before we proceed further so we handle the result here
                listener.onResponse(result);
            }, listener::onFailure));
        }
    }

    @Override
    protected void doLookupUser(String username, ActionListener<User> listener) {
        if (realmEnabled == false) {
            if (anonymousEnabled && AnonymousUser.isAnonymousUsername(username, config.globalSettings())) {
                listener.onResponse(anonymousUser);
            }
            listener.onResponse(null);
        } else if (isReserved(username, config.globalSettings()) == false) {
            listener.onResponse(null);
        } else if (AnonymousUser.isAnonymousUsername(username, config.globalSettings())) {
            listener.onResponse(anonymousEnabled ? anonymousUser : null);
        } else {
            getUserInfo(username, ActionListener.wrap((userInfo) -> {
                if (userInfo != null) {
                    listener.onResponse(getUser(username, userInfo));
                } else {
                    // this was a reserved username - don't allow this to go to another realm...
                    listener.onFailure(Exceptions.authenticationError("failed to lookup user [{}]", username));
                }
            }, listener::onFailure));
        }
    }

    public static boolean isReserved(String username, Settings settings) {
        assert username != null;
        switch (username) {
            case ElasticUser.NAME:
            case KibanaUser.NAME:
            case LogstashSystemUser.NAME:
                return XPackSettings.RESERVED_REALM_ENABLED_SETTING.get(settings);
            default:
                return AnonymousUser.isAnonymousUsername(username, settings);
        }
    }

    private User getUser(String username, ReservedUserInfo userInfo) {
        assert username != null;
        switch (username) {
            case ElasticUser.NAME:
                return new ElasticUser(userInfo.enabled);
            case KibanaUser.NAME:
                return new KibanaUser(userInfo.enabled);
            case LogstashSystemUser.NAME:
                return new LogstashSystemUser(userInfo.enabled);
            default:
                if (anonymousEnabled && anonymousUser.principal().equals(username)) {
                    return anonymousUser;
                }
                return null;
        }
    }


    public void users(ActionListener<Collection<User>> listener) {
        if (realmEnabled == false) {
            listener.onResponse(anonymousEnabled ? Collections.singletonList(anonymousUser) : Collections.emptyList());
        } else {
            nativeUsersStore.getAllReservedUserInfo(ActionListener.wrap((reservedUserInfos) -> {
                List<User> users = new ArrayList<>(4);

                ReservedUserInfo userInfo = reservedUserInfos.get(ElasticUser.NAME);
                users.add(new ElasticUser(userInfo == null || userInfo.enabled));

                userInfo = reservedUserInfos.get(KibanaUser.NAME);
                users.add(new KibanaUser(userInfo == null || userInfo.enabled));

                userInfo = reservedUserInfos.get(LogstashSystemUser.NAME);
                users.add(new LogstashSystemUser(userInfo == null || userInfo.enabled));

                if (anonymousEnabled) {
                    users.add(anonymousUser);
                }

                listener.onResponse(users);
            }, (e) -> {
                logger.error("failed to retrieve reserved users", e);
                listener.onResponse(anonymousEnabled ? Collections.singletonList(anonymousUser) : Collections.emptyList());
            }));
        }
    }


    private void getUserInfo(final String username, ActionListener<ReservedUserInfo> listener) {
        if (userIsDefinedForCurrentSecurityMapping(username) == false) {
            logger.debug("Marking user [{}] as disabled because the security mapping is not at the required version", username);
            listener.onResponse(DISABLED_DEFAULT_USER_INFO.deepClone());
        } else if (securityLifecycleService.isSecurityIndexExisting() == false) {
            listener.onResponse(bootstrapUserInfo.deepClone());
        } else {
            nativeUsersStore.getReservedUserInfo(username, ActionListener.wrap((userInfo) -> {
                if (userInfo == null) {
                    if (ElasticUser.NAME.equals(username)) {
                        listener.onResponse(bootstrapUserInfo.deepClone());
                    } else {
                        listener.onResponse(ENABLED_DEFAULT_USER_INFO.deepClone());
                    }
                } else {
                    listener.onResponse(userInfo);
                }
            }, (e) -> {
                logger.error((Supplier<?>) () ->
                        new ParameterizedMessage("failed to retrieve password hash for reserved user [{}]", username), e);
                listener.onResponse(null);
            }));
        }
    }

    private boolean userIsDefinedForCurrentSecurityMapping(String username) {
        final Version requiredVersion = getDefinedVersion(username);
        return securityLifecycleService.checkSecurityMappingVersion(requiredVersion::onOrBefore);
    }

    private Version getDefinedVersion(String username) {
        switch (username) {
            case LogstashSystemUser.NAME:
                return LogstashSystemUser.DEFINED_SINCE;
            default:
                return Version.V_5_0_0;
        }
    }

    public static void addSettings(List<Setting<?>> settingsList) {
        settingsList.add(ACCEPT_DEFAULT_PASSWORD_SETTING);
        settingsList.add(BOOTSTRAP_ELASTIC_PASSWORD);
    }
}
