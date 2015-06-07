/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.blur.thrift.sasl;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;


/**
 * PlainSaslServer.
 * 
 * This code originated in the Apache Hive Project. 
 * 
 * Sun JDK only provides PLAIN client and not server. This class implements the Plain SASL server
 * conforming to RFC #4616 (http://www.ietf.org/rfc/rfc4616.txt)
 */
public class PlainSaslServer implements SaslServer  {
  private String _user;
  private String _passwd;
  private String _authzId;
  private final CallbackHandler _handler;

  PlainSaslServer(CallbackHandler handler, String authMethodStr) throws SaslException {
    _handler = handler;
  }

  public String getMechanismName() {
    return "PLAIN";
  }

  public byte[] evaluateResponse(byte[] response) throws SaslException {
    try {
      // parse the response
      // message   = [authzid] UTF8NUL authcid UTF8NUL passwd'

      Deque<String> tokenList = new ArrayDeque<String>();
      StringBuilder messageToken = new StringBuilder();
      for (byte b : response) {
        if (b == 0) {
          tokenList.addLast(messageToken.toString());
          messageToken = new StringBuilder();
        } else {
          messageToken.append((char)b);
        }
      }
      tokenList.addLast(messageToken.toString());

      // validate response
      if ((tokenList.size() < 2) || (tokenList.size() > 3)) {
        throw new SaslException("Invalid message format");
      }
      _passwd = tokenList.removeLast();
      _user = tokenList.removeLast();
      // optional authzid
      if (!tokenList.isEmpty()) {
        _authzId = tokenList.removeLast();
      } else {
        _authzId = _user;
      }
      if (_user == null || _user.isEmpty()) {
        throw new SaslException("No user name provide");
      }
      if (_passwd == null || _passwd.isEmpty()) {
        throw new SaslException("No password name provide");
      }

      NameCallback nameCallback = new NameCallback("User");
      nameCallback.setName(_user);
      PasswordCallback pcCallback = new PasswordCallback("Password", false);
      pcCallback.setPassword(_passwd.toCharArray());
      AuthorizeCallback acCallback = new AuthorizeCallback(_user, _authzId);

      Callback[] cbList = new Callback[] {nameCallback, pcCallback, acCallback};
      _handler.handle(cbList);
      if (!acCallback.isAuthorized()) {
        throw new SaslException("Authentication failed");
      }
    } catch (IllegalStateException eL) {
      throw new SaslException("Invalid message format", eL);
    } catch (IOException eI) {
      throw new SaslException("Error validating the login", eI);
    } catch (UnsupportedCallbackException eU) {
      throw new SaslException("Error validating the login", eU);
    }
    return null;
  }

  public boolean isComplete() {
    return _user != null;
  }

  public String getAuthorizationID() {
    return _user;
  }

  public byte[] unwrap(byte[] incoming, int offset, int len) {
      throw new UnsupportedOperationException();
  }

  public byte[] wrap(byte[] outgoing, int offset, int len) {
    throw new UnsupportedOperationException();
  }

  public Object getNegotiatedProperty(String propName) {
    return null;
  }

  public void dispose() {}

  public static class SaslPlainServerFactory implements SaslServerFactory {

    public SaslServer createSaslServer(
      String mechanism, String protocol, String serverName, Map<String,?> props, CallbackHandler cbh)
    {
      if ("PLAIN".equals(mechanism)) {
        try {
          return new PlainSaslServer(cbh, protocol);
        } catch (SaslException e) {
          return null;
        }
      }
      return null;
    }

    public String[] getMechanismNames(Map<String, ?> props) {
      return new String[] { "PLAIN" };
    }
  }

  @SuppressWarnings("serial")
  public static class SaslPlainProvider extends java.security.Provider {
    public SaslPlainProvider() {
      super("BlurSaslPlain", 1.0, "Blur Plain SASL provider");
      put("SaslServerFactory.PLAIN", SaslPlainServerFactory.class.getName());
    }
  }
}


