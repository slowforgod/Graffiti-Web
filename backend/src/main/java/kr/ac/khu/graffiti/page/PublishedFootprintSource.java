package kr.ac.khu.graffiti.page;

import java.util.List;

/** Footprints of the PUBLISHED doodles of a page, read at startup to rebuild occupancy (D-25, INV-12). */
public interface PublishedFootprintSource {

    List<Footprint> load(long pageId, CellGeometry geometry);
}
