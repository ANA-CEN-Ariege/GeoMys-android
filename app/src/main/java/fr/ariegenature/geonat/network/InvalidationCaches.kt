package fr.ariegenature.geonat.network

/** Vide en cascade tous les caches mémoire process-wide qui dépendent de l'identité du
 *  serveur GeoNature (URL / login / mot de passe). À appeler par l'écran de configuration
 *  dès qu'un de ces 3 champs change — sans ça, un changement de serveur continue à servir
 *  des id_nomenclature, id_table_location, id_role, modules et LabelResolver issus de
 *  l'instance précédente, et les envois partent avec des FK invalides.
 *
 *  Les caches disque versionnés par module_code ([fr.ariegenature.geonat.store.MonitoringCache])
 *  ne sont pas vidés ici : ils restent valides tant que l'utilisateur ne change pas de
 *  serveur, et un changement de serveur impose de toute façon un nouveau sync explicite. */
fun invaliderCachesSession() {
    GeoNatureAuth.invaliderCache()
    MonitoringApi.invaliderCaches()
    GeoNatureUpload.invaliderCaches()
}
