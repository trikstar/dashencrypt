/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.castlabs.dash.dashfragmenter;

import com.castlabs.dash.dashfragmenter.cmdlines.ExitCodeException;

import java.io.IOException;

public interface Command {
    int run() throws IOException, ExitCodeException;

}