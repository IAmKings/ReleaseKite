#!/usr/bin/env python3
"""Check site-root and page-relative links against an isolated generated site."""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("site_links", Path(__file__).with_name("check-site-links.py"))
links = importlib.util.module_from_spec(spec)
spec.loader.exec_module(links)


class SiteLinksTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.original_site = links.SITE
        links.SITE = Path(self.directory.name).resolve()
        (links.SITE / "brand").mkdir()
        (links.SITE / "index.html").write_text("home")
        (links.SITE / "brand/mark.svg").write_text("<svg/>")
        self.page = links.SITE / "brand/index.html"
        self.page.write_text('<img src="/ReleaseKite/brand/mark.svg"><img src="mark.svg">')

    def tearDown(self):
        links.SITE = self.original_site
        self.directory.cleanup()

    def test_absolute_and_relative_asset_links_resolve_to_same_file(self):
        for raw in ("/ReleaseKite/brand/mark.svg", "mark.svg", "/ReleaseKite/brand/mark.svg?v=1#icon"):
            with self.subTest(raw=raw):
                self.assertEqual(links.SITE / "brand/mark.svg", links.local_target(self.page, raw))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, links.main())

    def test_absolute_site_root_and_parent_relative_page(self):
        for raw in ("/ReleaseKite/", "../index.html"):
            with self.subTest(raw=raw):
                self.assertEqual(links.SITE / "index.html", links.local_target(self.page, raw))

    def test_missing_absolute_asset_is_still_rejected(self):
        self.page.write_text('<img src="/ReleaseKite/brand/missing.svg">')
        error = io.StringIO()
        with contextlib.redirect_stderr(error):
            self.assertEqual(1, links.main())
        self.assertIn("missing.svg", error.getvalue())


if __name__ == "__main__":
    unittest.main()
