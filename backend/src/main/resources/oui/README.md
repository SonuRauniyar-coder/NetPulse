`manuf.tsv.gz` maps MAC prefixes (24, 28 or 36 bits, hex) to vendor names, one `PREFIX<TAB>Vendor` per line.

Generated from Wireshark's table (<https://www.wireshark.org/download/automated/data/manuf>), which is built from
the IEEE MA-L, MA-M and MA-S registries. Regenerate occasionally; new vendors are simply reported as unknown until then.
