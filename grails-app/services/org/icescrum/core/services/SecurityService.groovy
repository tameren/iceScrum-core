/*
 * Copyright (c) 2015 Kagilum SAS
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 * Vincent Barrier (vbarrier@kagilum.com)
 * Stéphane Maldini (stephane.maldini@icescrum.com)
 */

package org.icescrum.core.services

import org.springframework.security.core.context.SecurityContextHolder as SCH
import org.springframework.web.context.request.RequestContextHolder as RCH

import org.codehaus.groovy.grails.orm.hibernate.cfg.GrailsHibernateUtil
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.plugin.springsecurity.acl.AclClass
import grails.plugin.springsecurity.acl.AclObjectIdentity
import grails.plugin.springsecurity.acl.AclSid
import org.icescrum.core.domain.Product
import org.icescrum.core.domain.Team
import org.icescrum.core.domain.User
import org.icescrum.core.domain.security.Authority
import org.icescrum.core.domain.security.UserAuthority
import org.springframework.security.acls.domain.BasePermission
import org.springframework.security.acls.domain.PrincipalSid
import org.springframework.security.core.Authentication
import org.springframework.util.Assert
import static org.springframework.security.acls.domain.BasePermission.*
import org.springframework.security.acls.model.*

class SecurityService {

    def aclUtilService
    def objectIdentityRetrievalStrategy
    def springSecurityService
    def grailsUrlMappingsHolder
    def grailsApplication
    def aclService

    static final productOwnerPermissions = [BasePermission.WRITE]
    static final stakeHolderPermissions = [BasePermission.READ]
    static final teamMemberPermissions = [BasePermission.READ]
    static final scrumMasterPermissions = [BasePermission.WRITE]

    Acl secureDomain(o) {
        createAcl objectIdentityRetrievalStrategy.getObjectIdentity(o)
    }

    void unsecureDomain(o) {
        aclUtilService.deleteAcl GrailsHibernateUtil.unwrapIfProxy(o)
    }

    void changeOwner(User u, o) {
        aclUtilService.changeOwner GrailsHibernateUtil.unwrapIfProxy(o), u.username
        u.lastUpdated = new Date()
        u.save()
    }

    void createProductOwnerPermissions(User u, Product p) {
        aclUtilService.addPermission GrailsHibernateUtil.unwrapIfProxy(p), u.username, WRITE
        u.lastUpdated = new Date()
        u.save()
    }

    void deleteProductOwnerPermissions(User u, Product p) {
        aclUtilService.deletePermission GrailsHibernateUtil.unwrapIfProxy(p), u.username, WRITE
        u.lastUpdated = new Date()
        u.save()
    }

    void createTeamMemberPermissions(User u, Team t) {
        aclUtilService.addPermission GrailsHibernateUtil.unwrapIfProxy(t), u.username, READ
        u.lastUpdated = new Date()
        u.save()
    }

    void deleteTeamMemberPermissions(User u, Team t) {
        aclUtilService.deletePermission GrailsHibernateUtil.unwrapIfProxy(t), u.username, READ
        u.lastUpdated = new Date()
        u.save()
    }

    void createScrumMasterPermissions(User u, Team t) {
        aclUtilService.addPermission GrailsHibernateUtil.unwrapIfProxy(t), u.username, WRITE
        aclUtilService.addPermission GrailsHibernateUtil.unwrapIfProxy(t), u.username, ADMINISTRATION
        u.lastUpdated = new Date()
        u.save()
    }

    void deleteScrumMasterPermissions(User u, Team t) {
        aclUtilService.deletePermission GrailsHibernateUtil.unwrapIfProxy(t), u.username, WRITE
        aclUtilService.deletePermission GrailsHibernateUtil.unwrapIfProxy(t), u.username, ADMINISTRATION
        u.lastUpdated = new Date()
        u.save()
    }

    void createStakeHolderPermissions(User u, Product p) {
        aclUtilService.addPermission GrailsHibernateUtil.unwrapIfProxy(p), u.username, READ
        u.lastUpdated = new Date()
        u.save()
    }

    void deleteStakeHolderPermissions(User u, Product p) {
        aclUtilService.deletePermission GrailsHibernateUtil.unwrapIfProxy(p), u.username, READ
        u.lastUpdated = new Date()
        u.save()
    }

    @SuppressWarnings("GroovyMissingReturnStatement")
    boolean inProduct(product, auth) {
        if (!springSecurityService.isLoggedIn()) {
            return false
        }
        boolean authorized = productOwner(product, auth)
        if (!authorized) {
            def p
            if (!product) {
                def request = RCH.requestAttributes.currentRequest
                if (request.filtered) {
                    return request.inProduct
                } else {
                    product = parseCurrentRequestProduct(request)
                }
            } else if (product in Product) {
                p = product
                product = product.id
            }
            if (product) {
                def computeResult = {
                    if (!p) {
                        p = Product.get(product)
                    }
                    if (!p || !auth) {
                        return false
                    }
                    for (team in p.teams) {
                        if (inTeam(team, auth)) {
                            return true
                        }
                    }
                }
                authorized = computeResult()
            }
        }
        return authorized
    }

    boolean archivedProduct(product) {
        def p
        if (SpringSecurityUtils.ifAnyGranted(Authority.ROLE_ADMIN)) {
            return false
        }
        if (!product) {
            def request = RCH.requestAttributes.currentRequest
            if (request.filtered) {
                return request.archivedProduct ?: false
            } else {
                product = parseCurrentRequestProduct(request)
            }
        } else if (product in Product) {
            p = product
            product = product.id
        }
        if (product) {
            def computeResult = {
                if (!p) {
                    p = Product.get(product)
                }
                return p ? p.preferences.archived : false
            }
            return computeResult()
        } else {
            return false
        }
    }

    boolean inTeam(team, auth) {
        if (!springSecurityService.isLoggedIn()) {
            return false
        }
        teamMember(team, auth) || scrumMaster(team, auth)
    }

    Team openProductTeam(Long productId, Long principalId) {
        def computeResult = {
            def team = Team.productTeam(productId, principalId).list(max: 1)
            return team ? team[0] : null
        }
        return computeResult()
    }


    boolean scrumMaster(team, auth) {
        if (!springSecurityService.isLoggedIn()) {
            return false
        }
        def t = null
        if (!team) {
            def request = RCH.requestAttributes.currentRequest
            if (request.filtered) {
                return request.scrumMaster
            } else {
                def parsedProduct = parseCurrentRequestProduct(request)
                if (parsedProduct) {
                    def p = Product.get(parsedProduct)
                    //case product doesn't exist
                    if(!p){
                        return false
                    }
                    t = GrailsHibernateUtil.unwrapIfProxy(p.firstTeam)
                    team = t.id
                }
            }
        } else if (team in Team) {
            t = GrailsHibernateUtil.unwrapIfProxy(team)
            team = t.id
        }
        return isScrumMaster(team, auth, t) || isOwner(team, auth, grailsApplication.getDomainClass(Team.class.name).newInstance(), t)
    }

    boolean isScrumMaster(team, auth, t = null) {
        if (team) {
            if (SpringSecurityUtils.ifAnyGranted(Authority.ROLE_ADMIN)) {
                return true
            }
            def computeResult = {
                if (!t) {
                    t = Team.get(team)
                }
                if (!t || !auth) {
                    return false
                }
                return aclUtilService.hasPermission(auth, GrailsHibernateUtil.unwrapIfProxy(t), SecurityService.scrumMasterPermissions)
            }
            return computeResult()
        } else {
            return false
        }
    }

    boolean stakeHolder(product, auth, onlyPrivate, controllerName = null) {
        if (!springSecurityService.isLoggedIn() && onlyPrivate) {
            return false
        }
        def p = null
        def stakeHolder = false
        if (!product) {
            def request = RCH.requestAttributes.currentRequest
            if (request.filtered && !controllerName) {
                return request.stakeHolder
            } else {
                product = parseCurrentRequestProduct(request)
                if (request.stakeHolder) {
                    stakeHolder = request.stakeHolder
                }
            }
        } else if (product in Product) {
            p = GrailsHibernateUtil.unwrapIfProxy(product)
            product = product.id
        }
        if (product) {
            if (!p) {
                p = Product.get(product)
            }
            if (!p || !auth) {
                return false
            }
            if (SpringSecurityUtils.ifAnyGranted(Authority.ROLE_ADMIN)) {
                return true
            }
            def computeResult = {
                def access = stakeHolder ?: p.preferences.hidden ? aclUtilService.hasPermission(auth, GrailsHibernateUtil.unwrapIfProxy(p), SecurityService.stakeHolderPermissions) : !onlyPrivate
                if (access && controllerName) {
                    return !(controllerName in p.preferences.stakeHolderRestrictedViews?.split(','))
                } else {
                    return access
                }
            }
            return computeResult()
        } else {
            return false
        }
    }

    boolean productOwner(product, auth) {
        if (!springSecurityService.isLoggedIn()) {
            return false
        }
        def p = null
        if (!product) {
            def request = RCH.requestAttributes.currentRequest
            if (request.filtered) {
                return request.productOwner
            } else {
                product = parseCurrentRequestProduct(request)
            }
        } else if (product in Product) {
            p = GrailsHibernateUtil.unwrapIfProxy(product)
            product = product.id
        }
        def isPo = isProductOwner(product, auth, p)
        if (isPo) {
            return true
        } else if (product) {
            if (!p) {
                p = Product.get(product)
            }
            //case product doesn't exist
            if(!p){
                return false
            }
            Team t = GrailsHibernateUtil.unwrapIfProxy(p.firstTeam)
            long team = t.id
            return isOwner(team, auth, grailsApplication.getDomainClass(Team.class.name).newInstance(), t)
        } else {
            return false
        }
    }

    boolean admin(auth) {
        if (!springSecurityService.isLoggedIn()) {
            return false
        }
        return SpringSecurityUtils.ifAnyGranted(Authority.ROLE_ADMIN)
    }

    boolean isProductOwner(product, auth, p = null) {
        if (product) {
            if (SpringSecurityUtils.ifAnyGranted(Authority.ROLE_ADMIN)) {
                return true
            }
            def computeResult = {
                if (!p) {
                    p = Product.get(product)
                }
                if (!p || !auth) {
                    return false
                }
                return aclUtilService.hasPermission(auth, GrailsHibernateUtil.unwrapIfProxy(p), SecurityService.productOwnerPermissions)
            }
            return computeResult()
        } else {
            return false
        }
    }

    boolean teamMember(team, auth) {
        if (!springSecurityService.isLoggedIn()) {
            return false
        }
        def t
        if (!team) {
            def request = RCH.requestAttributes.currentRequest
            if (request.filtered) {
                return request.inTeam
            } else {
                def parsedProduct = parseCurrentRequestProduct(request)
                if (parsedProduct) {
                    if (SpringSecurityUtils.ifAnyGranted(Authority.ROLE_ADMIN)) {
                        return true
                    }
                    t = openProductTeam(parsedProduct, springSecurityService.principal.id)
                    team = t?.id
                }
            }
        } else if (team in Team) {
            t = GrailsHibernateUtil.unwrapIfProxy(team)
            team = team.id
        }
        if (team) {
            if (SpringSecurityUtils.ifAnyGranted(Authority.ROLE_ADMIN)) {
                return true
            }
            def computeResult = {
                if (!t) {
                    t = Team.get(team)
                }
                if (!t || !auth) {
                    return false
                }
                return aclUtilService.hasPermission(auth, GrailsHibernateUtil.unwrapIfProxy(t), SecurityService.teamMemberPermissions)
            }
            return computeResult()
        } else {
            return false
        }
    }

    boolean hasRoleAdmin(User user) {
        UserAuthority.countByAuthorityAndUser(Authority.findByAuthority(Authority.ROLE_ADMIN, [cache: true]), user, [cache: true])
    }

    Long parseCurrentRequestProduct(request) {
        def res = request['product_id']
        if (!res) {
            def param = request.getParameter('product')
            if (!param) {
                def mappingInfo = grailsUrlMappingsHolder.match(request.forwardURI.replaceFirst(request.contextPath, ''))
                res = mappingInfo?.parameters?.getAt('product')?.decodeProductKey()?.toLong()
            } else {
                res = param?.decodeProductKey()?.toLong()
            }
            request['product_id'] = res
        }
        return res
    }

    MutableAcl createAcl(ObjectIdentity objectIdentity, parent = null) throws AlreadyExistsException {
        Assert.notNull objectIdentity, 'Object Identity required'
        // Check this object identity hasn't already been persisted
        if (aclService.retrieveObjectIdentity(objectIdentity)) {
            throw new AlreadyExistsException("Object identity '$objectIdentity' already exists")
        }
        // Need to retrieve the current principal, in order to know who "owns" this ACL (can be changed later on)
        PrincipalSid sid = new PrincipalSid(SCH.context.authentication)
        // Create the acl_object_identity row
        createObjectIdentity objectIdentity, sid, parent
        return aclService.readAclById(objectIdentity)
    }

    protected void createObjectIdentity(ObjectIdentity object, Sid owner, parent = null) {
        AclSid ownerSid = aclService.createOrRetrieveSid(owner, true)
        AclClass aclClass = aclService.createOrRetrieveClass(object.type, true)
        aclService.save new AclObjectIdentity(
                aclClass: aclClass,
                objectId: object.identifier,
                owner: ownerSid,
                parent: parent,
                entriesInheriting: true)
    }

    public boolean owner(domain, Authentication auth) {
        if (!springSecurityService.isLoggedIn()) {
            return false
        }
        if (SpringSecurityUtils.ifAnyGranted(Authority.ROLE_ADMIN)) {
            return true
        }
        def d = null
        def domainClass
        if (!domain) {
            def request = RCH.requestAttributes.currentRequest
            if (request.filtered) {
                return request.owner
            } else {
                def parsedProduct = parseCurrentRequestProduct(request)
                if (parsedProduct) {
                    def p = Product.get(parsedProduct)
                    //case product doesn't exist
                    if(!p){
                        return false
                    }
                    d = GrailsHibernateUtil.unwrapIfProxy(p.firstTeam)
                    domain = d.id
                    domainClass = grailsApplication.getDomainClass(Team.class.name).newInstance()
                }
            }
        } else {
            d = GrailsHibernateUtil.unwrapIfProxy(domain)
            domainClass = d
            if (!d) {
                return false
            }
            domain = d.id
        }
        return isOwner(domain, auth, domainClass, d)
    }

    boolean isOwner(domain, auth, domainClass, d = null) {
        if (domain && domainClass) {
            def computeResult = {
                if (!d) {
                    d = domainClass.get(domain)
                }
                if (!d || !auth) {
                    return false
                }
                def acl = aclService.readAclById(objectIdentityRetrievalStrategy.getObjectIdentity(d))
                return acl.owner == new PrincipalSid((Authentication) auth)
            }
            return computeResult()
        } else {
            return false
        }
    }

    def filterRequest(force = false) {
        def request = RCH.requestAttributes.currentRequest
        if (!force && (!request || (request && request.filtered))) {
            return
        }
        request.filtered = force ? false : request.filtered

        request.scrumMaster = request.filtered ? request.scrumMaster : scrumMaster(null, springSecurityService.authentication)
        request.productOwner = request.filtered ? request.productOwner : productOwner(null, springSecurityService.authentication)
        request.teamMember = request.filtered ? request.teamMember : teamMember(null, springSecurityService.authentication)
        request.stakeHolder = request.filtered ? request.stakeHolder : stakeHolder(null, springSecurityService.authentication, false)
        request.owner = request.filtered ? request.owner : owner(null, springSecurityService.authentication)
        request.inProduct = request.filtered ? request.inProduct : request.scrumMaster ?: request.productOwner ?: request.teamMember ?: false
        request.inTeam = request.filtered ? request.inTeam : request.scrumMaster ?: request.teamMember ?: false
        request.admin = request.filtered ? request.admin : admin(springSecurityService.authentication) ?: false
        if (request.owner && !request.inProduct && !request.admin) {
            request.stakeholder = true
        }
        if ((request.inProduct || request.stakeHolder) && archivedProduct(null)) {
            request.scrumMaster = false
            request.productOwner = false
            request.teamMember = false
            request.inTeam = false
            request.inProduct = false
            request.owner = false
            request.archivedProduct = true
        }
        request.filtered = request.filtered ?: true
    }

    def getRolesRequest(force){
        filterRequest(force)
        def request = RCH.requestAttributes.currentRequest
        return [productOwner: request.productOwner,
                scrumMaster: request.scrumMaster,
                teamMember: request.teamMember,
                stakeHolder: request.stakeHolder,
                admin: request.admin]
    }
}
