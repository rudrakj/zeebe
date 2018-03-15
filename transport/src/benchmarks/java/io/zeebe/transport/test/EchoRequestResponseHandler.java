/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.zeebe.transport.test;

import io.zeebe.transport.*;
import org.agrona.DirectBuffer;

public class EchoRequestResponseHandler implements ServerRequestHandler
{
    protected ServerResponse response = new ServerResponse();

    @Override
    public boolean onRequest(ServerOutput output, RemoteAddress remoteAddress, DirectBuffer buffer, int offset,
            int length, long requestId)
    {
        response
            .reset()
            .buffer(buffer, offset, length)
            .requestId(requestId)
            .remoteStreamId(remoteAddress.getStreamId());
        return output.sendResponse(response);
    }
}