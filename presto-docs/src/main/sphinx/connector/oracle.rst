================
Oracle Connector
================

The Oracle connector allows querying and creating tables in an external Oracle
database. Connectors let Presto join data provided by different databases,
like Oracle and Hive, or different Oracle database instances.

Configuration
-------------

To configure the Oracle connector as the ``oracle`` catalog, create a file named
``oracle.properties`` in ``etc/catalog``. Include the following connection
properties in the file:

.. code-block:: none

    connector.name=oracle
    connection-url=jdbc:oracle:thin:@example.net:1521/ORCLCDB
    connection-user=root
    connection-password=secret

By default, the Oracle connector uses connection pooling for performance
improvement. The below configuration shows the typical default values. To update
them, change the properties in the catalog configuration file:

.. code-block:: properties

    oracle.connection-pool.max-size=30
    oracle.connection-pool.min-size=1
    oracle.connection-pool.inactive-timeout=20m

To disable connection pooling, update properties to include the following:

.. code-block:: none

    oracle.connection-pool.enabled=false

Multiple Oracle servers
^^^^^^^^^^^^^^^^^^^^^^^

If you want to connect to multiple Oracle servers, configure another instance of
the Oracle connector as a separate catalog.

To add another Oracle catalog, create a new properties file. For example, if
you name the property file ``sales.properties``, Presto creates a catalog named
sales.

Querying Oracle
---------------

The Oracle connector provides a schema for every Oracle database. 

Run ``SHOW SCHEMAS`` to see the available Oracle databases::

    SHOW SCHEMAS FROM oracle;

If you used a different name for your catalog properties file, use that catalog
name instead of ``oracle``.

.. note::
    The Oracle user must have access to the table in order to access it from Presto.
    The user configuration, in the connection properties file, determines your
    privileges in these schemas.

Examples
^^^^^^^^

If you have an Oracle database named ``web``, run ``SHOW TABLES`` to see the
tables it contains::

    SHOW TABLES FROM oracle.web;

To see a list of the columns in the ``clicks`` table in the ``web``
database, run either of the following::

    DESCRIBE oracle.web.clicks;
    SHOW COLUMNS FROM oracle.web.clicks;

To access the clicks table in the web database, run the following::

    SELECT * FROM oracle.web.clicks;

Mapping data types between Presto and Oracle
--------------------------------------------

Both Oracle and Presto have types that are not supported by the Oracle
connector. The following sections explain their type mapping.

Oracle to Presto type mapping
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Presto supports selecting Oracle database types. This table shows the Oracle to
Presto data type mapping:


.. list-table:: Oracle to Presto type mapping
  :widths: 20, 20, 60
  :header-rows: 1

  * - Oracle database type
    - Presto type
    - Notes
  * - ``NUMBER(p, s)``
    - ``DECIMAL(p, s)``
    -  See :ref:`number mapping`
  * - ``NUMBER(p)``
    - ``DECIMAL(p, 0)``
    - See :ref:`number mapping`
  * - ``FLOAT[(p)]``
    - ``DOUBLE``
    -
  * - ``BINARY_FLOAT``
    - ``REAL``
    -
  * - ``BINARY_DOUBLE``
    - ``DOUBLE``
    -
  * - ``VARCHAR2(n CHAR)``
    - ``VARCHAR(n)``
    -
  * - ``VARCHAR2(n BYTE)``
    - ``VARCHAR(n)``
    -
  * - ``NVARCHAR2(n)``
    - ``VARCHAR(n)``
    -
  * - ``CHAR(n)``
    - ``CHAR(n)``
    -
  * - ``NCHAR(n)``
    - ``CHAR(n)``
    -
  * - ``CLOB``
    - ``VARCHAR``
    -
  * - ``NCLOB``
    - ``VARCHAR``
    -
  * - ``RAW(n)``
    - ``VARBINARY``
    -
  * - ``BLOB``
    - ``VARBINARY``
    -
  * - ``DATE``
    - ``TIMESTAMP``
    - See :ref:`datetime mapping`
  * - ``TIMESTAMP(p)``
    - ``TIMESTAMP``
    - See :ref:`datetime mapping`
  * - ``TIMESTAMP(p) WITH TIME ZONE``
    - ``TIMESTAMP WITH TIME ZONE``
    - See :ref:`datetime mapping`

If an Oracle table uses a type not listed in the above table, then you can use the
``unsupported-type.handling`` configuration property to specify Presto behavior.
For example:

- If ``unsupported-type.handling`` is set to ``FAIL``, then the
  querying of an unsupported table fails.
- If ``unsupported-type.handling`` is set to ``IGNORE``, 
  then you can't see the unsupported types in Presto.
- If ``unsupported-type.handling`` is set to ``CONVERT_TO_VARCHAR``, 
  then the column is exposed as unbounded ``VARCHAR``.

Presto to Oracle type mapping
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Presto supports creating tables with the following types in an Oracle database.
The table shows the mappings from Presto to Oracle data types:

.. note::
   For types not listed in the table below, Presto can't perform the ``CREATE
   TABLE <table> AS SELECT`` operations. When data is inserted into existing
   tables ``Oracle to Presto`` type mapping is used.

.. list-table:: Presto to Oracle Type Mapping
  :widths: 20, 20, 60
  :header-rows: 1

  * - Presto type
    - Oracle database type
    - Notes
  * - ``TINYINT``
    - ``NUMBER(3)``
    -
  * - ``SMALLINT``
    - ``NUMBER(5)``
    -
  * - ``INTEGER``
    - ``NUMBER(10)``
    -
  * - ``BIGINT``
    - ``NUMBER(19)``
    -
  * - ``DECIMAL(p, s)``
    - ``NUMBER(p, s)``
    -
  * - ``REAL``
    - ``BINARY_FLOAT``
    -
  * - ``DOUBLE``
    - ``BINARY_DOUBLE``
    -
  * - ``VARCHAR``
    - ``NCLOB``
    -
  * - ``VARCHAR(n)``
    - ``VARCHAR2(n CHAR)`` or ``NCLOB``
    - See :ref:`character mapping`
  * - ``CHAR(n)``
    - ``CHAR(n CHAR)`` or ``NCLOB``
    - See :ref:`character mapping`
  * - ``VARBINARY``
    - ``BLOB``
    -
  * - ``DATE``
    - ``DATE``
    - See :ref:`datetime mapping`
  * - ``TIMESTAMP``
    - ``TIMESTAMP(3)``
    - See :ref:`datetime mapping`
  * - ``TIMESTAMP WITH TIME ZONE``
    - ``TIMESTAMP(3) WITH TIME ZONE``
    - See :ref:`datetime mapping`

.. _number mapping:

Mapping numeric types
^^^^^^^^^^^^^^^^^^^^^

An Oracle ``NUMBER(p, s)`` maps to Presto's ``DECIMAL(p, s)`` except in these
conditions:

- No precision is specified for the column (example: ``NUMBER`` or
  ``NUMBER(*)``), unless ``oracle.number.default-scale`` is set. 
- Scale (``s`` ) is greater than precision. 
- Precision (``p`` ) is greater than 38. 
- Scale is negative and the difference between ``p`` and ``s`` is greater than
  38, unless ``oracle.number.rounding-mode`` is set to a different value than
  ``UNNECESSARY``. 
   
If ``s`` is negative, ``NUMBER(p, s)`` maps to ``DECIMAL(p + s, 0)``.

For Oracle ``NUMBER`` (without precision and scale), you can change
``oracle.number.default-scale=s`` and map the column to ``DECIMAL(38, s)``.

.. _datetime mapping:

Mapping datetime types
^^^^^^^^^^^^^^^^^^^^^^

Selecting a timestamp with fractional second precision (``p``) greater than 3
truncates the fractional seconds to three digits instead of rounding it.

Oracle ``DATE`` type may store hours, minutes, and seconds, so it is mapped
to Presto ``TIMESTAMP``.

.. warning::

  Due to date and time differences in the libraries used by Presto and the
  Oracle JDBC driver, attempting to insert or select a datetime value earlier
  than ``1582-10-15`` results in an incorrect date inserted.

.. _character mapping:

Mapping character types
^^^^^^^^^^^^^^^^^^^^^^^

Presto's ``VARCHAR(n)`` maps to ``VARCHAR2(n CHAR)`` if ``n`` is no greater than
4000. A larger or unbounded ``VARCHAR`` maps to ``NCLOB``.

Presto's ``CHAR(n)`` maps to ``CHAR(n CHAR)`` if ``n`` is no greater than 2000.
A larger ``CHAR`` maps to ``NCLOB``.

Using ``CREATE TABLE AS`` to create an ``NCLOB`` column from a ``CHAR`` value
removes the trailing spaces from the initial values for the column. Inserting
``CHAR`` values into existing ``NCLOB`` columns keeps the trailing spaces. For
example::

    presto> CREATE TABLE vals AS SELECT CAST('A' as CHAR(2001)) col;
    presto> INSERT INTO vals (col) VALUES (CAST('BB' as CHAR(2001)));
    presto> SELECT LENGTH(col) FROM vals;
    2001
    1

Attempting to write a ``CHAR`` that doesn't fit in the column's actual size
fails. This is also true for the equivalent ``VARCHAR`` types.

Type mapping configuration properties
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. list-table:: Type Mapping Properties
  :widths: 20, 20, 50, 10
  :header-rows: 1

  * - Configuration property name
    - Session property name
    - Description
    - Default
  * - ``unsupported-type.handling-strategy``
    - ``unsupported_type_handling_strategy``
    - Configures how unsupported column data types are handled:

      - ``IGNORE`` - column is not accessible.
      - ``CONVERT_TO_VARCHAR`` - column is converted to unbounded ``VARCHAR``.

    - ``IGNORE``
  * - ``oracle.number.default-scale``
    - ``number_default_scale``               
    - Default Presto ``DECIMAL`` scale for Oracle ``NUMBER`` (without precision 
      and scale) date type. When not set then such column is treated as not 
      supported.
    - not set
  * - ``oracle.number.rounding-mode``
    - ``number_rounding_mode``
    - Rounding mode for the Oracle ``NUMBER`` data type. This is useful when 
      Oracle ``NUMBER`` data type specifies higher scale than is supported in 
      Presto. Possible values are:

      - ``UNNECESSARY`` - Rounding mode to assert that the 
        requested operation has an exact result, 
        hence no rounding is necessary.
      - ``CEILING`` - Rounding mode to round towards
        positive infinity.
      - ``FLOOR`` - Rounding mode to round towards negative
        infinity.
      - ``HALF_DOWN`` - Rounding mode to round towards
        ``nearest neighbor`` unless both neighbors are
        equidistant, in which case rounding down is used.
      - ``HALF_EVEN`` - Rounding mode to round towards the
        ``nearest neighbor`` unless both neighbors are equidistant,
        in which case rounding towards the even neighbor is
        performed.
      - ``HALF_UP`` - Rounding mode to round towards
        ``nearest neighbor`` unless both neighbors are
        equidistant, in which case rounding up is used
      - ``UP`` - Rounding mode to round towards zero.
      - ``DOWN`` - Rounding mode to round towards zero.

    - ``UNNECESSARY``

Synonyms
--------

Based on performance reasons, Presto disables support for Oracle ``SYNONYM``. To
include ``SYNONYM``, add the following configuration property: 

.. code-block:: none

    oracle.synonyms.enabled=true

Pushdown
--------

The connector supports :doc:`pushdown </optimizer/pushdown>` for optimized query processing.

Limitations
-----------

The following SQL statements are not supported:

* :doc:`/sql/delete`
* :doc:`/sql/alter-table`
* :doc:`/sql/grant`
* :doc:`/sql/revoke`
* :doc:`/sql/show-grants`
* :doc:`/sql/show-roles`
* :doc:`/sql/show-role-grants`
