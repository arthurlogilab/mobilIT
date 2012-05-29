/**
 * This file is part of MobilIT.
 *
 * MobilIT is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MobilIT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MobilIT. If not, see <http://www.gnu.org/licenses/>.
 * 
 * @See https://github.com/sim51/mobilIT
 */
package fr.mobilit.neo4j.server.service;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;

import org.neo4j.gis.spatial.SpatialDatabaseService;

import fr.mobilit.neo4j.server.exception.MobilITException;
import fr.mobilit.neo4j.server.pojo.POI;
import fr.mobilit.neo4j.server.utils.Constant;

public abstract class CycleRent {

    protected SpatialDatabaseService spatial;

    /**
     * Method to get a cycle service by the geocode.
     * 
     * @param spatial
     * @param geocode
     * @return
     * @throws MobilITException
     */
    public static CycleRent getService(SpatialDatabaseService spatial, String geocode) throws MobilITException {
        Class serviceClass = Constant.CYCLE_SERVICE.get(geocode);
        if (serviceClass != null) {
            try {
                Constructor serviceConstructor = serviceClass.getConstructor(SpatialDatabaseService.class);
                CycleRent service = (CycleRent) serviceConstructor.newInstance(spatial);
                return service;
            } catch (Exception e) {
                throw new MobilITException(e.getMessage(), e.getCause());
            }
        }
        else {
            throw new MobilITException("There is no cycle service for geocode " + geocode);
        }
    }

    /**
     * Method to import all rent cycle station to the database.
     * 
     * @return the list of station imported
     * @throws MobilITException
     */
    public abstract List<POI> importStation() throws MobilITException;

    /**
     * Method to get the nearest rent cycle station by longitude and latitude.
     * 
     * @param lon the longitude
     * @param lat the latitude
     * @param status : 0-> whatever ; 1-> with free cycle ; 2-> with free slot
     * @return
     */
    public List<POI> getNearestStation(Double lon, Double lat, Integer status) throws MobilITException {
        return null;
    }

    /**
     * Method to get the sttus of a station (avaible & free slot).
     * 
     * @param id the id of the station.
     * @return
     */
    public abstract Map<String, Integer> getStation(String id) throws MobilITException;

}
