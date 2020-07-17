=========================
Release 336 (16 Jun 2020)
=========================

General Changes
---------------

* Fix failure when querying timestamp columns from older clients. (:issue:`4036`)
* Improve reporting of configuration errors. (:issue:`4050`)
* Fix rare failure when recording server stats in T-Digests. (:issue:`3965`)

Security Changes
----------------

* Add table access rules to :ref:`file-based-system-access-control`. (:issue:`3951`)
* Add new ``default`` system access control that allows all operations except user impersonation. (:issue:`4040`)

Hive Connector Changes
----------------------

* Fix incorrect query results when reading Parquet files with predicates
  when ``hive.parquet.use-column-names`` is set to ``false`` (the default). (:issue:`3574`)
