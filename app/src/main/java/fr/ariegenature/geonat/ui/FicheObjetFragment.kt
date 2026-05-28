package fr.ariegenature.geonat.ui

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import fr.ariegenature.geonat.R
import fr.ariegenature.geonat.databinding.FragmentFicheObjetBinding
import fr.ariegenature.geonat.network.MonitoringApi
import fr.ariegenature.geonat.store.GeoNatureConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** Fiche générique d'un objet monitoring (site, sites_group, visite, observation, …).
 *  Réutilisable à tous les niveaux de la hiérarchie : un clic sur un enfant ouvre la même
 *  fragment avec un nouveau `objectType`/`id`, ce qui permet le drill-down arbitraire sans
 *  un fragment par object_type. Affichage piloté par le schéma du protocole. */
class FicheObjetFragment : Fragment() {
    private var _binding: FragmentFicheObjetBinding? = null
    private val binding get() = _binding!!

    /** Fil d'Ariane complet de CET objet (= "Protocole › … › nom de l'objet"), reçu via
     *  l'argument de navigation `fil` (l'appelant le connaît : c'est la ligne tapée). Le
     *  dernier segment est l'objet courant ; les précédents préfixent le fil des enfants. */
    private var filCourant: List<FilSegment> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFicheObjetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.root.applySystemBarInsets(includeIme = true)

        val moduleCode = arguments?.getString("moduleCode") ?: return navUp()
        val objectType = arguments?.getString("objectType") ?: return navUp()
        val id = arguments?.getInt("id", -1)?.takeIf { it > 0 } ?: return navUp()

        binding.btnRetour.setOnClickListener { findNavController().navigateUp() }

        filCourant = decoderFil(arguments?.getString("fil"))
        // Dernier segment = cet objet (non cliquable, on y est déjà) ; ancêtres cliquables.
        appliquerFilAriane(binding.tvFil, findNavController(), moduleCode, filCourant, dernierCliquable = false)

        chargerEtAfficher(moduleCode, objectType, id)
    }

    override fun onResume() {
        super.onResume()
        // Au retour d'une saisie locale, on re-render la liste des enfants pour
        // intégrer la nouvelle entrée outbox. On ne re-fetch pas l'objet serveur si
        // déjà chargé — juste un re-render léger.
        val moduleCode = arguments?.getString("moduleCode") ?: return
        val objectType = arguments?.getString("objectType") ?: return
        val id = arguments?.getInt("id", -1)?.takeIf { it > 0 } ?: return
        if (objetCharge != null) {
            binding.llEnfants.removeAllViews()
            afficherEnfants(objetCharge!!, schemaCharge, resolverCharge ?: MonitoringApi.LabelResolver())
        }
    }

    /** Cache du dernier objet chargé + son schéma + son resolver, pour pouvoir
     *  re-render la liste des enfants sans refaire les requêtes serveur (utile au
     *  retour d'une saisie locale via onResume). */
    private var objetCharge: MonitoringApi.MonitoringObjet? = null
    private var schemaCharge: Map<String, MonitoringApi.MonitoringSchemaObjet>? = null
    private var resolverCharge: MonitoringApi.LabelResolver? = null

    private fun navUp() { findNavController().navigateUp() }

    private fun chargerEtAfficher(moduleCode: String, objectType: String, id: Int) {
        binding.progressFiche.visibility = View.VISIBLE
        binding.tvErreur.visibility = View.GONE
        binding.tvTitre.text = ""
        binding.tvType.text = ""
        binding.llProprietes.removeAllViews()
        binding.llEnfants.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            val config = GeoNatureConfig(requireContext())
            // runCatching dans chaque async : sinon une exception (ex. 403) propage au scope
            // parent (structured concurrency) et plante le launch avant le catch sur await().
            val objetDeferred = async { runCatching { MonitoringApi.chargerObjet(config, moduleCode, objectType, id) } }
            val schemaDeferred = async { runCatching { MonitoringApi.chargerSchemaProtocole(config, moduleCode) } }
            val objetRes = objetDeferred.await()
            val schemaRes = schemaDeferred.await()
            if (!isAdded) return@launch
            val objet = objetRes.getOrElse { e ->
                binding.progressFiche.visibility = View.GONE
                binding.tvErreur.visibility = View.VISIBLE
                binding.tvErreur.text = fr.ariegenature.geonat.network.humaniserErreurReseau(e)
                return@launch
            }
            val schema = schemaRes.getOrNull()
            val resolver = if (schema != null) {
                runCatching { MonitoringApi.chargerResolveurLabels(config, moduleCode, schema) }
                    .getOrNull() ?: MonitoringApi.LabelResolver()
            } else MonitoringApi.LabelResolver()
            if (!isAdded) return@launch
            binding.progressFiche.visibility = View.GONE
            afficher(objet, schema, resolver)
        }
    }

    private fun afficher(
        objet: MonitoringApi.MonitoringObjet,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
        resolver: MonitoringApi.LabelResolver,
    ) {
        val schemaCe = schema?.get(objet.type)
        // Titre = valeur du nameField déclaré par le schéma, sinon heuristique.
        val nom = schemaCe?.nameField?.let { objet.proprietes[it] }?.takeIf { it.isNotEmpty() }
            ?: MonitoringApi.extraireNomHeuristique(objet.proprietes, objet.type, objet.id)
        binding.tvTitre.text = nom
        binding.tvType.text = schemaCe?.label
            ?: schemaCe?.labelList
            ?: labelTypeParDefaut(objet.type)

        // Position / Geometry retirée de la fiche : reste accessible via le bouton 👁 carte.
        afficherProprietes(objet, schemaCe, resolver)
        afficherEnfants(objet, schema, resolver)
        // Mémorise pour le re-render par onResume au retour d'une saisie locale.
        objetCharge = objet
        schemaCharge = schema
        resolverCharge = resolver
    }

    /** Affiche les propriétés de l'objet sous forme "Label : valeur".
     *  Priorité au schéma serveur : `display_properties` dicte l'ORDRE et le SOUS-ENSEMBLE
     *  des champs à afficher (= ce que montre l'interface web), `attribut_label` dicte le
     *  libellé. Sans schéma exploitable, fallback sur l'ancien filtre par blacklist. */
    private fun afficherProprietes(
        objet: MonitoringApi.MonitoringObjet,
        schemaCe: MonitoringApi.MonitoringSchemaObjet?,
        resolver: MonitoringApi.LabelResolver,
    ) {
        val nameField = schemaCe?.nameField
        val displayList = schemaCe?.displayProperties.orEmpty()

        val cles: List<String> = if (displayList.isNotEmpty()) {
            displayList.filter { it != nameField && objet.proprietes.containsKey(it) }
        } else {
            // Fallback (vieux schéma sans display_properties) : tous les scalaires moins
            // une blacklist de champs techniques.
            objet.proprietes.keys.filter { k ->
                k != nameField &&
                    !k.startsWith("id_") &&
                    !k.startsWith("uuid_") &&
                    !k.startsWith("meta_") &&
                    k != "additional_data_keys" &&
                    k != "pk" &&
                    k != "altitude_min" && k != "altitude_max" &&
                    !k.endsWith("_code") &&
                    k != "geom" && k != "geometry" && !k.startsWith("geom_")
            }
        }
        if (cles.isEmpty()) return

        val ctx = requireContext()
        val density = resources.displayMetrics.density
        cles.forEach { k ->
            val v = objet.proprietes[k] ?: return@forEach
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val label = TextView(ctx).apply {
                // Schéma serveur en priorité (attribut_label), sinon mapping FR par défaut.
                text = schemaCe?.properties?.get(k)?.label ?: labelChamp(k)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(couleurSecondaire(ctx))
            }
            val value = TextView(ctx).apply {
                // Résolution ID → label via le resolver si type_util est géré, sinon formatage.
                val prop = schemaCe?.properties?.get(k)
                val resolu = if (prop != null) resolver.resoudre(prop, v) else null
                text = resolu ?: formatValeur(k, v)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
            row.addView(label)
            row.addView(value)
            binding.llProprietes.addView(row)
        }
    }

    /** Liste les enfants directs par type (visites, observations, etc.), chacun cliquable
     *  pour ouvrir sa propre fiche (drill-down récursif). */
    private fun afficherEnfants(
        objet: MonitoringApi.MonitoringObjet,
        schema: Map<String, MonitoringApi.MonitoringSchemaObjet>?,
        resolver: MonitoringApi.LabelResolver,
    ) {
        // Toujours clear la liste au début : afficherEnfants peut être appelé plusieurs
        // fois sur la même vue (chargerEtAfficher async + onResume + …) — sans clear,
        // on cumule les entrées (doublon).
        binding.llEnfants.removeAllViews()
        // On affiche la section même si pas d'enfants serveur : il peut y avoir des
        // saisies locales (outbox) rattachées à cet objet à montrer.
        val saisiesLocalesIci = saisiesLocalesAttacheesA(objet)
        android.util.Log.i("FicheObjetFragment",
            "afficherEnfants : objet=${objet.type}#${objet.id}, enfants serveur=${objet.enfants.keys}, " +
                "saisies locales=${saisiesLocalesIci.size}")
        // Pas de return early si tout est vide : il peut encore y avoir un type d'enfant
        // déclaré au schéma qui mérite un bouton "+" pour amorcer la première saisie
        // (cf. 3e passe plus bas).
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val borderlessAttr = android.util.TypedValue().also {
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
        }.resourceId
        val schemaObjet = schema?.get(objet.type)
        objet.enfants.forEach { (type, itemsBruts) ->
            val saisiesLocalesCeType = saisiesLocalesIci.filter { it.objectType == type }
            // Skip seulement si pas d'enfant serveur ET pas de saisie locale du même type —
            // sinon on rend les saisies locales sous un header dédié.
            if (itemsBruts.isEmpty() && saisiesLocalesCeType.isEmpty()) return@forEach
            val schemaType = schema?.get(type)
            // Re-derive nom via schema.nameField puis tri alphabétique sur ce nom
            // (les `sorts` du schéma sont volontairement ignorés — cf. SuiviDetailFragment).
            val nf = schemaType?.nameField
            val itemsAffines = if (nf != null) itemsBruts.map { e ->
                val nomSchema = e.proprietes[nf]
                if (!nomSchema.isNullOrEmpty()) e.copy(nom = nomSchema) else e
            } else itemsBruts
            val items = MonitoringApi.trierEnfants(itemsAffines, emptyList())
            val typeLabel = schema?.get(type)?.let { it.labelList ?: it.label }
                ?: labelTypeParDefaut(type)
            // Type d'enfant qui sera créé via le bouton "+" : on regarde si le type de
            // l'enfant courant déclare lui-même un childrenType de saisie (visite, releve,
            // observation…). Si oui, chaque ligne d'enfant porte un "+" qui crée cette
            // saisie. Sinon, pas de bouton "+". (cf. respect strict du schéma serveur.)
            val typeSaisieEnfant = schemaType?.childrenTypes?.firstOrNull {
                fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(it)
            }
            // Sur les enfants SERVEUR uniquement, on neutralise le "+" quand la ligne EST
            // elle-même une saisie déjà historisée côté serveur (visite/passage/observation
            // déjà saisie). Empêche d'ajouter des sous-obs à une visite ancienne : les
            // observations doivent être saisies pendant la visite, pas a posteriori.
            // Les saisies locales (PENDING/ERROR) conservent leur "+" car ce sont les saisies
            // en cours sur lesquelles l'utilisateur enchaîne légitimement.
            val typeSaisieEnfantServeur = typeSaisieEnfant
                ?.takeIf { !fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(type) }
            binding.llEnfants.addView(creerHeaderType(
                type, typeLabel, items.size + saisiesLocalesCeType.size,
                objet, schemaObjet, density, borderlessAttr,
            ))
            val borderless = borderlessAttr
            items.forEach { e ->
                val nom = e.nom
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val bloc = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameTv = TextView(ctx).apply {
                    text = nom
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
                bloc.addView(nameTv)
                val sousTitre = sousTitrePourEnfant(e, schemaType, resolver)
                if (sousTitre.isNotEmpty()) {
                    val sub = TextView(ctx).apply {
                        text = sousTitre
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(couleurSecondaire(ctx))
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    bloc.addView(sub)
                }
                val btnInfo = ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_info)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.jaune_clair))
                    setBackgroundResource(borderless)
                    contentDescription = "Détails"
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setOnClickListener {
                        if (e.id > 0) {
                            findNavController().navigate(
                                R.id.action_fiche_to_fiche,
                                bundleOf(
                                    "moduleCode" to objet.moduleCode,
                                    "objectType" to type,
                                    "id" to e.id,
                                    "fil" to encoderFil(filCourant + FilSegment(type, e.id, nom)),
                                )
                            )
                        }
                    }
                }
                val aGeometrie = schemaType?.geometryType != null
                val btnCarte = if (aGeometrie) ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_eye)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.jaune_clair))
                    setBackgroundResource(borderless)
                    contentDescription = "Voir sur carte"
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setOnClickListener {
                        if (e.id > 0) {
                            findNavController().navigate(
                                R.id.action_fiche_to_carte,
                                bundleOf(
                                    "moduleCode" to objet.moduleCode,
                                    "objectType" to type,
                                    "id" to e.id,
                                    "titre" to nom,
                                    // Fil de la carte = fil courant + l'enfant (= la fiche
                                    // dont la carte va s'ouvrir). Permet au tap d'un sous-
                                    // enfant sur la carte de poursuivre la chaîne réelle.
                                    "fil" to encoderFil(filCourant + FilSegment(type, e.id, nom)),
                                )
                            )
                        }
                    }
                } else null
                // Bouton "+" : nouvelle saisie attachée à cet enfant. Visible uniquement
                // si le schéma de l'enfant déclare un type de saisie comme childrenType
                // ET si cet enfant n'est pas lui-même une saisie déjà historisée côté serveur
                // (typeSaisieEnfantServeur encapsule les 2 conditions).
                val btnPlus = if (typeSaisieEnfantServeur != null) ImageButton(ctx).apply {
                    setImageResource(R.drawable.ic_add)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(ctx, R.color.jaune_clair))
                    setBackgroundResource(borderless)
                    contentDescription = "Nouvelle saisie"
                    layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                    setOnClickListener {
                        if (e.id > 0) {
                            findNavController().navigate(
                                R.id.action_fiche_to_nouvelle_visite,
                                bundleOf(
                                    "moduleCode" to objet.moduleCode,
                                    "parentObjectType" to type,
                                    "parentId" to e.id,
                                    "titreSite" to nom,
                                    "childObjectType" to typeSaisieEnfantServeur,
                                    "fil" to encoderFil(filCourant + FilSegment(type, e.id, nom)),
                                )
                            )
                        }
                    }
                } else null
                row.addView(bloc)
                row.addView(btnInfo)
                if (btnCarte != null) row.addView(btnCarte)
                if (btnPlus != null) row.addView(btnPlus)
                binding.llEnfants.addView(row)
            }
            // Saisies locales du même type d'enfant (= visites/obs créées hors-ligne et
            // pas encore envoyées au serveur). On les affiche sous le header existant
            // avec un look distinctif (fond ambre + icône ⏳) et un bouton + qui permet
            // d'enchaîner les sous-saisies, en pointant vers le parent local via UUID.
            // (saisiesLocalesCeType est déjà calculé en début de forEach.)
            saisiesLocalesCeType.forEach { saisie ->
                binding.llEnfants.addView(creerLigneSaisieLocale(objet, type, saisie, typeSaisieEnfant, density, borderless))
            }
        }
        // Types de saisies locales pas encore présents dans le schéma `objet.enfants`
        // (cas typique : première visite jamais saisie sur le serveur pour ce parent →
        // l'API ne déclare pas encore le type d'enfant dans la fiche, mais le schéma le
        // permet et l'outbox en contient).
        val typesDejaRendus = objet.enfants.keys.toSet()
        val saisiesLocalesOrphelines = saisiesLocalesAttacheesA(objet).filterNot { it.objectType in typesDejaRendus }
        saisiesLocalesOrphelines.groupBy { it.objectType }.forEach { (type, saisies) ->
            val schemaType = schema?.get(type)
            val typeLabel = schemaType?.let { it.labelList ?: it.label } ?: labelTypeParDefaut(type)
            val typeSaisieEnfant = schemaType?.childrenTypes?.firstOrNull {
                fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(it)
            }
            binding.llEnfants.addView(creerHeaderType(
                type, typeLabel, saisies.size, objet, schemaObjet, density, borderlessAttr,
            ))
            saisies.forEach { saisie ->
                binding.llEnfants.addView(
                    creerLigneSaisieLocale(objet, type, saisie, typeSaisieEnfant, density, borderlessAttr),
                )
            }
        }

        // 3e passe — types de saisie déclarés au schéma mais sans aucune occurrence (ni
        // côté serveur, ni en outbox). Ex : un point d'écoute STOM sans visite encore.
        // On rend juste un header "(0)" — le bouton "+" du header (cf. creerHeaderType)
        // suffit pour amorcer la première saisie.
        val typesDejaAffiches = objet.enfants.keys + saisiesLocalesOrphelines.map { it.objectType }.toSet()
        val tousChildren = schemaObjet?.childrenTypes.orEmpty()
        val typesSaisieAttendus = tousChildren.filter { ct ->
            fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(ct) && ct !in typesDejaAffiches
        }
        typesSaisieAttendus.forEach { type ->
            val schemaType = schema?.get(type)
            val typeLabel = schemaType?.let { it.labelList ?: it.label } ?: labelTypeParDefaut(type)
            binding.llEnfants.addView(creerHeaderType(
                type, typeLabel, 0, objet, schemaObjet, density, borderlessAttr,
            ))
        }
    }

    /** Header d'un type d'enfant ("Visites (3)") avec, à droite, un bouton "+ nouvelle
     *  saisie" quand [type] est un type de saisie (visite/observation/relevé…). Le clic
     *  ouvre la création avec le type courant comme parent — la nouvelle entrée sera
     *  donc un sibling des items déjà listés sous ce header. */
    private fun creerHeaderType(
        type: String,
        label: String,
        count: Int,
        objet: MonitoringApi.MonitoringObjet,
        schemaObjetParent: MonitoringApi.MonitoringSchemaObjet?,
        density: Float,
        borderlessId: Int,
    ): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (12 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
        }
        row.addView(TextView(ctx).apply {
            text = "$label ($count)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(couleurSecondaire(ctx))
            isAllCaps = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (fr.ariegenature.geonat.network.MonitoringSync.estTypeSaisie(type)) {
            row.addView(ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.jaune_clair))
                setBackgroundResource(borderlessId)
                contentDescription = "Nouvelle $label"
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(), (40 * density).toInt(),
                )
                setOnClickListener {
                    findNavController().navigate(
                        R.id.action_fiche_to_nouvelle_visite,
                        bundleOf(
                            "moduleCode" to objet.moduleCode,
                            "parentObjectType" to objet.type,
                            "parentId" to objet.id,
                            "titreSite" to (schemaObjetParent?.nameField?.let { objet.proprietes[it] } ?: ""),
                            "childObjectType" to type,
                            "fil" to encoderFil(filCourant),
                        ),
                    )
                }
            })
        }
        return row
    }

    /** Retourne les saisies locales (outbox, non SENT) qui sont rattachées à [objet]
     *  comme parent direct — soit par id serveur, soit par UUID si l'objet courant est
     *  lui-même une saisie locale (cas peu fréquent, à étendre plus tard). */
    private fun saisiesLocalesAttacheesA(
        objet: MonitoringApi.MonitoringObjet,
    ): List<fr.ariegenature.geonat.store.SaisieEnAttente> {
        val toutes = fr.ariegenature.geonat.store.OutboxMonitoring.tout()
        val result = toutes.filter {
            it.etat != fr.ariegenature.geonat.store.SaisieEnAttente.Etat.SENT &&
                it.moduleCode == objet.moduleCode &&
                it.parentObjectType == objet.type &&
                it.parentIdServeur == objet.id
        }
        if (toutes.isNotEmpty() && result.isEmpty()) {
            // Log diag : si on a des saisies en outbox mais aucune ne matche cet objet,
            // utile pour identifier les mismatch parentObjectType / parentIdServeur.
            val recap = toutes.joinToString(", ") {
                "{type=${it.objectType}, parent=${it.parentObjectType}#${it.parentIdServeur}, etat=${it.etat}}"
            }
            android.util.Log.i("FicheObjetFragment",
                "saisiesLocalesAttacheesA(${objet.type}#${objet.id}, module=${objet.moduleCode}) = 0 / ${toutes.size}. " +
                    "Saisies présentes : $recap")
        }
        return result
    }

    /** Rend une ligne pour une saisie locale (= dans l'outbox). Look distinctif :
     *  fond ambre, icône ⏳, sous-titre "Saisie locale du <date>". Bouton + actif pour
     *  enchaîner des sous-saisies attachées à cette saisie locale via parentUuidLocal. */
    private fun creerLigneSaisieLocale(
        objet: MonitoringApi.MonitoringObjet,
        type: String,
        saisie: fr.ariegenature.geonat.store.SaisieEnAttente,
        typeSaisieEnfant: String?,
        density: Float,
        borderless: Int,
    ): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFF8E1.toInt())  // ambre très clair
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (4 * density).toInt() }
        }
        val bloc = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val icone = when (saisie.etat) {
            fr.ariegenature.geonat.store.SaisieEnAttente.Etat.ERROR -> "⚠"
            fr.ariegenature.geonat.store.SaisieEnAttente.Etat.SENDING -> "🚀"
            else -> "⏳"
        }
        val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE)
        bloc.addView(TextView(ctx).apply {
            text = "$icone Saisie locale"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })
        bloc.addView(TextView(ctx).apply {
            text = "${fmt.format(java.util.Date(saisie.dateLocale))} · à envoyer"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            // Statut "à envoyer" → teinte ambrée/orange Material (colorSecondary du thème,
            // orange en mode sombre). Avant : #8B6914 codé en dur (illisible sur fond accueil).
            setTextColor(com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorSecondary, 0xFFFF6D00.toInt(),
            ))
        })
        row.addView(bloc)
        // Bouton + : pareil que pour les enfants serveur, mais pointe le parent via
        // parentUuidLocal (l'envoi en cascade côté OutboxEnvoi résoudra l'UUID en id
        // serveur quand le parent local sera SENT).
        if (typeSaisieEnfant != null) {
            val btnPlus = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.jaune_clair))
                setBackgroundResource(borderless)
                contentDescription = "Nouvelle saisie"
                layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
                setOnClickListener {
                    findNavController().navigate(
                        R.id.action_fiche_to_nouvelle_visite,
                        bundleOf(
                            "moduleCode" to objet.moduleCode,
                            "parentObjectType" to type,
                            "parentUuidLocal" to saisie.uuid,
                            "titreSite" to "Saisie locale du ${fmt.format(java.util.Date(saisie.dateLocale))}",
                            "childObjectType" to typeSaisieEnfant,
                            "fil" to encoderFil(filCourant),
                        )
                    )
                }
            }
            row.addView(btnPlus)
        }
        return row
    }

    /** Label FR pour un nom de champ de gn_module_monitoring (best-effort), sinon clé nettoyée. */
    private fun labelChamp(key: String): String = when (key) {
        "base_site_name", "sites_group_name" -> "Nom"
        "base_site_code", "sites_group_code" -> "Code"
        "base_site_description", "sites_group_description" -> "Description"
        "comment_site", "comment" -> "Commentaire"
        "desc_habitat" -> "Habitat"
        "prospect_surf" -> "Surface prospectée (m²)"
        "nb_visits" -> "Nombre de visites"
        "last_visit" -> "Dernière visite"
        "first_use_date" -> "Mise en service"
        "visit_date_min", "date_min" -> "Date de visite"
        "visit_date_max", "date_max" -> "Date de fin"
        "comments" -> "Commentaires"
        "altitude_min" -> "Altitude min (m)"
        "altitude_max" -> "Altitude max (m)"
        else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Format léger : booleans en oui/non, dates ISO raccourcies, sinon raw. */
    private fun formatValeur(key: String, v: String): String {
        if (v == "true") return "oui"
        if (v == "false") return "non"
        // Date ISO "YYYY-MM-DD..." → "YYYY-MM-DD" simple
        if (v.length >= 10 && v[4] == '-' && v[7] == '-') return v.substring(0, 10)
        return v
    }

    /** Sous-titre d'un row enfant : valeurs des champs de `schema.display_list` séparées par
     *  " · ", dates ISO formatées, IDs résolus en labels via le resolver, sans le nameField. */
    private fun sousTitrePourEnfant(
        e: MonitoringApi.MonitoringEnfant,
        schemaType: MonitoringApi.MonitoringSchemaObjet?,
        resolver: MonitoringApi.LabelResolver,
    ): String {
        val displayList = schemaType?.displayList.orEmpty()
        if (displayList.isEmpty()) return ""
        val nameField = schemaType?.nameField
        return displayList
            .filter { it != nameField }
            .mapNotNull { k ->
                val v = e.proprietes[k]?.takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
                val prop = schemaType?.properties?.get(k)
                if (prop != null) resolver.resoudre(prop, v)?.let { return@mapNotNull it }
                if (v.length >= 10 && v[4] == '-' && v[7] == '-')
                    "${v.substring(8, 10)}/${v.substring(5, 7)}/${v.substring(0, 4)}"
                else v
            }
            .joinToString(" · ")
    }

    /** Fallback générique quand le schéma serveur ne fournit pas de `label`. On capitalise
     *  le code technique et on remplace les `_` par des espaces — pas de mapping FR figé
     *  qui dépendrait des conventions d'un protocole. C'est à l'admin GeoNature de poser
     *  un label propre côté schéma si le rendu par défaut ne convient pas. */
    private fun labelTypeParDefaut(type: String): String =
        type.replace('_', ' ').replaceFirstChar { it.uppercase() }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}