===========
JDBC Driver
===========

The Presto `JDBC driver <https://en.wikipedia.org/wiki/JDBC_driver>`_ allows
users to access Presto using Java-based applications, and other non-Java
applications running in a JVM. Both desktop and server-side applications, such
as those used for reporting and database development, use the JDBC driver.

Requirements
------------

The JDBC driver is compatible with Java versions 8 or higher, and can be used with 
applications running on Java virtual machines version 8 or higher.

Installing
----------

Download :maven_download:`jdbc` and add it to the classpath of your Java application.

The driver is also available from Maven Central:

.. parsed-literal::

    <dependency>
        <groupId>io.prestosql</groupId>
        <artifactId>presto-jdbc</artifactId>
        <version>\ |version|\ </version>
    </dependency>

We recommend using the latest version of the JDBC driver. A list of all
available versions can be found in the `Maven Central Repository
<https://repo1.maven.org/maven2/io/prestosql/presto-jdbc/>`_. Navigate to the
directory for the desired version, and select the ``presto-jdbc-xxx.jar`` file
to download, where ``xxx`` is the version number.

Once downloaded, you must add the JAR file to a directory in the classpath 
of users on systems where they will access Presto.

After you have downloaded the JDBC driver and added it to your 
classpath, you'll typically need to restart your application in order to 
recognize the new driver. Then, depending on your application, you 
may need to manually register and configure the driver.

Registering and configuring the driver
--------------------------------------

Drivers are commonly loaded automatically by applications once they are added to
its classpath. If your application does not, such as is the case for some
GUI-based SQL editors, read this section. The steps to register the JDBC driver
in a UI or on the command line depend upon the specific application you are
using. Please check your application's documentation. 

Once registered, you must also configure the connection information as described
in the following section. 

Connecting
----------

When your driver is loaded, registered and configured, you are ready to connect
to Presto from your application. The following JDBC URL formats are supported:

.. code-block:: none

    jdbc:presto://host:port
    jdbc:presto://host:port/catalog
    jdbc:presto://host:port/catalog/schema

The following is an example of a JDBC URL used to create a connection:

.. code-block:: none

    jdbc:presto://example.net:8080/hive/sales

This example JDBC URL locates a Presto instance running on port ``8080`` on
``example.net``, with the catalog ``hive`` and the schema ``sales`` defined. 

Connection parameters
---------------------

The driver supports various parameters that may be set as URL parameters,
or as properties passed to ``DriverManager``. Both of the following
examples are equivalent:

.. code-block:: java

    // URL parameters
    String url = "jdbc:presto://example.net:8080/hive/sales";
    Properties properties = new Properties();
    properties.setProperty("user", "test");
    properties.setProperty("password", "secret");
    properties.setProperty("SSL", "true");
    Connection connection = DriverManager.getConnection(url, properties);

    // properties
    String url = "jdbc:presto://example.net:8080/hive/sales?user=test&password=secret&SSL=true";
    Connection connection = DriverManager.getConnection(url);

These methods may be mixed; some parameters may be specified in the URL,
while others are specified using properties. However, the same parameter
may not be specified using both methods.

Parameter reference
-------------------

====================================== =======================================================================
Name                                   Description
====================================== =======================================================================
``user``                               Username to use for authentication and authorization.
``password``                           Password to use for LDAP authentication.
``socksProxy``                         SOCKS proxy host and port. Example: ``localhost:1080``
``httpProxy``                          HTTP proxy host and port. Example: ``localhost:8888``
``clientInfo``                         Extra information about the client.
``clientTags``                         Client tags for selecting resource groups. Example: ``abc,xyz``
``traceToken``                         Trace token for correlating requests across systems.
``source``                             Source name for the Presto query. This parameter should be used in
                                       preference to ``ApplicationName``. Thus, it takes precedence
                                       over ``ApplicationName`` and/or ``applicationNamePrefix``.
``applicationNamePrefix``              Prefix to append to any specified ``ApplicationName`` client info
                                       property, which is used to set the source name for the Presto query
                                       if the ``source`` parameter has not been set. If neither this
                                       property nor ``ApplicationName`` or ``source`` are set, the source
                                       name for the query is ``presto-jdbc``.
``accessToken``                        Access token for token based authentication.
``SSL``                                Use HTTPS for connections
``SSLKeyStorePath``                    The location of the Java KeyStore file that contains the certificate
                                       and private key to use for authentication.
``SSLKeyStorePassword``                The password for the KeyStore.
``SSLKeyStoreType``                    The type of the KeyStore. The default type is provided by the Java
                                       ``keystore.type`` security property or ``jks`` if none exists.
``SSLTrustStorePath``                  The location of the Java TrustStore file to use.
                                       to validate HTTPS server certificates.
``SSLTrustStorePassword``              The password for the TrustStore.
``SSLTrustStoreType``                  The type of the TrustStore. The default type is provided by the Java
                                       ``keystore.type`` security property or ``jks`` if none exists.
``KerberosRemoteServiceName``          Presto coordinator Kerberos service name. This parameter is
                                       required for Kerberos authentication.
``KerberosPrincipal``                  The principal to use when authenticating to the Presto coordinator.
``KerberosUseCanonicalHostname``       Use the canonical hostname of the Presto coordinator for the Kerberos
                                       service principal by first resolving the hostname to an IP address
                                       and then doing a reverse DNS lookup for that IP address.
                                       This is enabled by default.
``KerberosServicePrincipalPattern``    Presto coordinator Kerberos service principal pattern. The default is
                                       ``${SERVICE}@${HOST}``. ``${SERVICE}`` is replaced with the value of
                                       ``KerberosRemoteServiceName`` and ``${HOST}`` is replaced with the
                                       hostname of the coordinator (after canonicalization if enabled).
``KerberosConfigPath``                 Kerberos configuration file.
``KerberosKeytabPath``                 Kerberos keytab file.
``KerberosCredentialCachePath``        Kerberos credential cache.
``useSessionTimeZone``                 Should dates and timestamps use the session time zone (default: false).
                                       Note that this property only exists for backward compatibility with the
                                       previous behavior and will be removed in the future.
``extraCredentials``                   Extra credentials for connecting to external services,
                                       specified as a list of key-value pairs. For example,
                                       ``foo:bar;abc:xyz`` creates the credential named ``abc``
                                       with value ``xyz`` and the credential named ``foo`` with value ``bar``.
``roles``                              Authorization roles to use for catalogs, specified as a list of
                                       key-value pairs for the catalog and role. For example,
                                       ``catalog1:roleA;catalog2:roleB`` sets ``roleA``
                                       for ``catalog1`` and ``roleB`` for ``catalog2``.
``sessionProperties``                  Session properties to set for the system and for catalogs,
                                       specified as a list of key-value pairs.
                                       For example, ``abc:xyz;example.foo:bar`` sets the system property
                                       ``abc`` to the value ``xyz`` and the ``foo`` property for
                                       catalog ``example`` to the value ``bar``.
====================================== =======================================================================
