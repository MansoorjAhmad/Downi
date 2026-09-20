"""Vercel entry point for DOWNI Cloud Boost.

The implementation stays in cloud/api so it can also be run and documented as a
standalone relay. Vercel discovers functions only from this root-level api folder.
"""

from cloud.api.extract import Handler as handler
