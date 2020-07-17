====================
Optimizer Properties
====================

``optimizer.dictionary-aggregation``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* **Type:** ``boolean``
* **Default value:** ``false``

Enables optimization for aggregations on dictionaries. This can also be specified
on a per-query basis using the ``dictionary_aggregation`` session property.

``optimizer.optimize-hash-generation``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* **Type:** ``boolean``
* **Default value:** ``true``

Compute hash codes for distribution, joins, and aggregations early during execution,
allowing result to be shared between operations later in the query. This can reduce
CPU usage by avoiding computing the same hash multiple times, but at the cost of
additional network transfer for the hashes. In most cases it decreases overall
query processing time. This can also be specified on a per-query basis using the
``optimize_hash_generation`` session property.

It is often helpful to disable this property, when using :doc:`/sql/explain` in order
to make the query plan easier to read.

``optimizer.optimize-metadata-queries``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* **Type:** ``boolean``
* **Default value:** ``false``

Enable optimization of some aggregations by using values that are stored as metadata.
This allows Presto to execute some simple queries in constant time. Currently, this
optimization applies to ``max``, ``min`` and ``approx_distinct`` of partition
keys and other aggregation insensitive to the cardinality of the input,including
``DISTINCT`` aggregates. Using this may speed up some queries significantly.

The main drawback is that it can produce incorrect results, if the connector returns
partition keys for partitions that have no rows. In particular, the Hive connector
can return empty partitions, if they were created by other systems. Presto cannot
create them.

``optimizer.push-aggregation-through-join``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* **Type:** ``boolean``
* **Default value:** ``true``

When an aggregation is above an outer join and all columns from the outer side of the join
are in the grouping clause, the aggregation is pushed below the outer join. This optimization
is particularly useful for correlated scalar subqueries, which get rewritten to an aggregation
over an outer join. For example::

    SELECT * FROM item i
        WHERE i.i_current_price > (
            SELECT AVG(j.i_current_price) FROM item j
                WHERE i.i_category = j.i_category);

Enabling this optimization can substantially speed up queries by reducing
the amount of data that needs to be processed by the join.  However, it may slow down some
queries that have very selective joins. This can also be specified on a per-query basis using
the ``push_aggregation_through_join`` session property.

``optimizer.push-table-write-through-union``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* **Type:** ``boolean``
* **Default value:** ``true``

Parallelize writes when using ``UNION ALL`` in queries that write data. This improves the
speed of writing output tables in ``UNION ALL`` queries, because these writes do not require
additional synchronization when collecting results. Enabling this optimization can improve
``UNION ALL`` speed, when write speed is not yet saturated. However, it may slow down queries
in an already heavily loaded system. This can also be specified on a per-query basis
using the ``push_table_write_through_union`` session property.


``optimizer.join-reordering-strategy``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* **Type:** ``string``
* **Allowed values:** ``AUTOMATIC``, ``ELIMINATE_CROSS_JOINS``, ``NONE``
* **Default value:** ``AUTOMATIC``

The join reordering strategy to use.  ``NONE`` maintains the order the tables are listed in the
query.  ``ELIMINATE_CROSS_JOINS`` reorders joins to eliminate cross joins, where possible, and
otherwise maintains the original query order. When reordering joins, it also strives to maintain the
original table order as much as possible. ``AUTOMATIC`` enumerates possible orders, and uses
statistics-based cost estimation to determine the least cost order. If stats are not available, or if
for any reason a cost could not be computed, the ``ELIMINATE_CROSS_JOINS`` strategy is used. This can
be specified on a per-query basis using the ``join_reordering_strategy`` session property.

``optimizer.max-reordered-joins``
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

* **Type:** ``integer``
* **Default value:** ``9``

When optimizer.join-reordering-strategy is set to cost-based, this property determines
the maximum number of joins that can be reordered at once.

.. warning::

    The number of possible join orders scales factorially with the number of
    relations, so increasing this value can cause serious performance issues.
