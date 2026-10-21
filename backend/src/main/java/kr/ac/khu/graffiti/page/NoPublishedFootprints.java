package kr.ac.khu.graffiti.page;

import java.util.List;
import org.springframework.stereotype.Component;

/** The doodle table does not exist yet; 06-publish replaces this with a query over PUBLISHED doodles. */
@Component
class NoPublishedFootprints implements PublishedFootprintSource {

    @Override
    public List<Footprint> load(long pageId, CellGeometry geometry) {
        return List.of();
    }
}
