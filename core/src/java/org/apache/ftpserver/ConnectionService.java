/* ====================================================================
 * Copyright 2002 - 2004
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
 *
 *
 * $Id$
 */
package org.apache.ftpserver;

import java.io.File;
import java.util.List;
import java.util.Vector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;
import org.apache.ftpserver.util.Message;
import org.apache.ftpserver.interfaces.FtpConnectionObserver;
import org.apache.ftpserver.interfaces.SpyConnectionInterface;
import org.apache.ftpserver.interfaces.FtpConnectionMonitor;
import org.apache.ftpserver.usermanager.User;
import org.apache.ftpserver.usermanager.UserManagerInterface;

/**
 * Ftp connection service class. It tracks all ftp connections.
 *
 * @author <a href="mailto:rana_b@yahoo.com">Rana Bhattacharyya</a>
 */
public
class ConnectionService {

    private FtpConnectionObserver mObserver;
    private AbstractFtpConfig mConfig;
    private FtpConnectionMonitor ftpConnectionMonitor;
    private Timer mTimer;
    private Vector mConList;


    /**
     * Constructor. Start scheduler job.
     */
    public ConnectionService(AbstractFtpConfig cfg, FtpConnectionMonitor ftpConnectionMonitor) throws UserManagerException {
        mConfig = cfg;
        this.ftpConnectionMonitor = ftpConnectionMonitor;
        mConList = new Vector();

        // default users creation
        if (mConfig.mbCreateUsers)
            createDefaultUsers();

        // set timer to remove inactive users and load data
        mTimer = new Timer();
        TimerTask timerTask = new TimerTask() {
            public void run() {
                timerTask();
            }
        };
        mTimer.schedule(timerTask, 0, mConfig.getSchedulerInterval()*1000);
    }

   /**
    * Create default users (admin/anonymous) if necessary
    */
   private void createDefaultUsers() throws UserManagerException {
        UserManagerInterface userManager = mConfig.getUserManager();

        // create admin user
        String adminName = userManager.getAdminName();
        if(!userManager.doesExist(adminName)) {
            ftpConnectionMonitor.creatingUser(adminName);
            User adminUser = new User();
            adminUser.setName(adminName);
            adminUser.setPassword(adminName);
            adminUser.setEnabled(true);
            adminUser.getVirtualDirectory().setWritePermission(true);
            adminUser.setMaxUploadRate(0);
            adminUser.setMaxDownloadRate(0);
            adminUser.getVirtualDirectory().setRootDirectory(mConfig.getDefaultRoot());
            adminUser.setMaxIdleTime(mConfig.getDefaultIdleTime());
            userManager.save(adminUser);
        }

        // create anonymous user
        if (mConfig.isAnonymousLoginAllowed()
                && !userManager.doesExist(FtpUser.ANONYMOUS)) {
            ftpConnectionMonitor.creatingUser(FtpUser.ANONYMOUS);
            User anonUser = new User();
            anonUser.setName(FtpUser.ANONYMOUS);
            anonUser.setPassword("");
            anonUser.setEnabled(true);
            anonUser.getVirtualDirectory().setWritePermission(false);
            anonUser.setMaxUploadRate(4800);
            anonUser.setMaxDownloadRate(4800);
            anonUser.getVirtualDirectory().setRootDirectory(mConfig.getDefaultRoot());
            anonUser.setMaxIdleTime(mConfig.getDefaultIdleTime());
            userManager.save(anonUser);
        }
    }


    /**
     * It returns a list of all the currently connected users.
     */
    public List getAllUsers() {
        List userList = new ArrayList();
        synchronized(mConList) {
            for(Iterator conIt=mConList.iterator(); conIt.hasNext(); ) {
                BaseFtpConnection conObj = (BaseFtpConnection)conIt.next();
                if (conObj != null) {
                    userList.add(conObj.getUser());
                }
            }
        }
        return userList;
    }

    /**
     * Set user manager observer.
     */
    public void setObserver(FtpConnectionObserver obsr ) {
        mObserver = obsr;
        synchronized(mConList) {
            for(Iterator conIt=mConList.iterator(); conIt.hasNext(); ) {
                BaseFtpConnection conObj = (BaseFtpConnection)conIt.next();
                if (conObj != null) {
                    conObj.setObserver(mObserver);
                }
            }
        }
    }

    /**
     * Get the observer.
     */
    public FtpConnectionObserver getObserver() {
        return mObserver;
    }

    /**
     * User login method. If successfull, populates the user object.
     */
    public boolean login(final FtpUserImpl thisUser) {

        // already logged in
        if(thisUser.hasLoggedIn()) {
            return true;
        }

        // get name and password
        String user = thisUser.getName();
        String password = thisUser.getPassword();
        if( (user == null) || (password == null) ) {
            return false;
        }

        // authenticate user
        UserManagerInterface userManager = mConfig.getUserManager();
        boolean bAnonymous = thisUser.getIsAnonymous();
        if ( !(bAnonymous || userManager.authenticate(user, password)) ) {
            ftpConnectionMonitor.authFailed(user);
            return false;
        }

        // populate user properties
        if (!populateProperties(thisUser, user)){
            return false;
        }

        // user enable check
        if(!thisUser.getEnabled()) {
            return false;
        }

        // connection limit check
        if (!checkConnection(thisUser)){
            return false;
        }

        thisUser.login();
        thisUser.setPassword(null);

        // create user home if necessary
        if( !createHome(thisUser) ) {
            return false;
        }
        ftpConnectionMonitor.userLogin(thisUser);

        // update global statistics
        mConfig.getStatistics().setLogin(thisUser.getIsAnonymous());
        return true;
    }


    /**
     * Close ftp connection for this session id.
     */
    public void closeConnection(final String sessId) {
        BaseFtpConnection con = null;
        synchronized(mConList) {
            con = getConnection(sessId);
            if (con != null) {
                mConList.remove(con);
            }
        }

        // close connection
        if (con != null) {

            // logout notification
            final FtpUserImpl thisUser = con.getUser();
            if (thisUser.hasLoggedIn()) {
                mConfig.getStatistics().setLogout(thisUser.getIsAnonymous());
            }

            // close socket
            con.stop();

            // send message
            Message msg = new Message() {
                public void execute() {
                    FtpConnectionObserver observer = mObserver;
                    if(observer != null) {
                        observer.removeConnection(thisUser);
                    }
                }
            };
            mConfig.getMessageQueue().add(msg);
            mConfig.getStatistics().setCloseConnection();
        }
    }


    /**
     * Close all - close all the connections.
     */
    public void closeAllConnections() {
        List allUsers = getAllUsers();
        for( Iterator userIt = allUsers.iterator(); userIt.hasNext(); ) {
            FtpUserImpl user = (FtpUserImpl)userIt.next();
            closeConnection(user.getSessionId());
        }
    }

    /**
     * Populate user properties
     */
    private boolean populateProperties(FtpUserImpl thisUser, String user) {

        // get the existing user
        UserManagerInterface userManager = mConfig.getUserManager();
        User existUser = userManager.getUserByName(user);
        if(existUser == null) {
            return false;
        }

        // map properties
        thisUser.getVirtualDirectory().setRootDirectory(new File(existUser.getVirtualDirectory().getRootDirectory()));
        thisUser.setEnabled(existUser.getEnabled());
        thisUser.getVirtualDirectory().setWritePermission(existUser.getVirtualDirectory().getWritePermission());
        thisUser.setMaxIdleTime(existUser.getMaxIdleTime());
        thisUser.setMaxUploadRate(existUser.getMaxUploadRate());
        thisUser.setMaxDownloadRate(existUser.getMaxDownloadRate());
        return true;
    }

    /**
     * Connection limit check.
     */
    private boolean checkConnection(FtpUserImpl thisUser) {
        int maxLogins = mConfig.getMaxConnections();
        int maxAnonLogins = mConfig.getMaxAnonymousLogins();
        int anonNbr = mConfig.getStatistics().getAnonLoginNbr();
        int totalNbr = mConfig.getStatistics().getLoginNbr();

        // final check
        if(thisUser.getIsAnonymous()) {
            if(!mConfig.isAnonymousLoginAllowed()) {
               return false;
            }
            if( (anonNbr>=maxAnonLogins) || (totalNbr>=maxLogins) ) {
               return false;
            }
            ftpConnectionMonitor.anonConnection(thisUser);
        }
        else {
            if(totalNbr>=maxLogins) {
                return false;
            }
        }
        return true;
    }


    /**
     * Create user home directory if necessary
     */
    private boolean createHome(FtpUserImpl user) {

        File userHome = new File( user.getVirtualDirectory().getRootDirectory() );
        if( userHome.exists() ) {
            if( !userHome.isDirectory() ) {
                ftpConnectionMonitor.userHomeNotADir(userHome, user);
                return false;
            }
        }
        else {
            if( mConfig.isCreateHome() ) {
                ftpConnectionMonitor.creatingHome(userHome, user);
                if( !userHome.mkdirs() ) {
                    ftpConnectionMonitor.cannotCreateHome(userHome, user);
                    return false;
                }
            }
            else {
                ftpConnectionMonitor.cannotFindHome(userHome, user);
                return false;
            }
        }

        return true;
    }



    /**
     * New connection has been established - not yet logged-in.
     */
    public void newConnection(final BaseFtpConnection newCon) {

        // null user - ignore
        if (newCon == null) {
            return;
        }

        final FtpUserImpl newUser = newCon.getUser();

        mConList.add(newCon);
        newUser.setMaxIdleTime(mConfig.getDefaultIdleTime());
        newUser.getVirtualDirectory().setRootDirectory(mConfig.getDefaultRoot());
        newCon.setObserver(mObserver);
        ftpConnectionMonitor.newConnectionFrom(newUser);

        // notify observer about a new connection
        final FtpConnectionObserver observer = mObserver;
        if (observer != null) {
            Message msg = new Message() {
                public void execute() {
                    observer.newConnection(newUser);
                }
            };
            mConfig.getMessageQueue().add(msg);
        }

        // update global statistics
        mConfig.getStatistics().setOpenConnection();
    }



    /**
     * Set connection spy object
     */
    public void setSpyObject(String sessId, SpyConnectionInterface spy) {
        BaseFtpConnection con = getConnection(sessId);
        if (con != null) {
            con.setSpyObject(spy);
        }
    }

    /**
     * Get connection object
     */
    public BaseFtpConnection getConnection(String sessId) {
        BaseFtpConnection con = null;
        synchronized(mConList) {
            for(Iterator conIt=mConList.iterator(); conIt.hasNext(); ) {
                BaseFtpConnection conObj = (BaseFtpConnection)conIt.next();
                if (conObj != null) {
                    if ( conObj.getUser().getSessionId().equals(sessId) ) {
                        con = conObj;
                        break;
                    }
                }
            }
        }
        return con;
    }

    /**
     * Reset all spy objects
     */
    public void resetAllSpyObjects() {
        synchronized(mConList) {
            for(Iterator conIt=mConList.iterator(); conIt.hasNext(); ) {
                BaseFtpConnection conObj = (BaseFtpConnection)conIt.next();
                if (conObj != null) {
                    conObj.setSpyObject(null);
                }
            }
        }
    }

    /**
     * Timer thread will call this method periodically to
     * close inactice connections and load user information.
     */
    public void timerTask() {

        // get inactive user list
        ArrayList inactiveUserList = new ArrayList();
        long currTime = System.currentTimeMillis();
        synchronized(mConList) {
            for( Iterator conIt=mConList.iterator(); conIt.hasNext(); ) {
                BaseFtpConnection con = (BaseFtpConnection)conIt.next();
                if (con != null) {
                    FtpUserImpl user = con.getUser();
                    if (!user.isActive(currTime)) {
                        inactiveUserList.add(user);
                    }
                }
            }
        }

        // remove inactive users
        for( Iterator userIt=inactiveUserList.iterator(); userIt.hasNext(); ) {
            FtpUserImpl user = (FtpUserImpl)userIt.next();
            ftpConnectionMonitor.removingIdleUser(user);
            closeConnection(user.getSessionId());
        }

        // reload user data
        UserManagerInterface userManager = mConfig.getUserManager();
        try {
            userManager.reload();
        }
        catch(Exception ex) {
            ftpConnectionMonitor.timerError(ex);
        }
    }


    /**
     * Dispose connection service. If logs out all the connected
     * users and stops the cleaner thread.
     */
    public void dispose() {

        // close all connections
        if (mConList != null) {
            closeAllConnections();
            mConList = null;
        }

        // stop timer
        if (mTimer != null) {
            mTimer.cancel();
            mTimer = null;
        }
    }


}