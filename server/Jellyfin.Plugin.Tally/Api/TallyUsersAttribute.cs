using System;
using System.Threading.Tasks;
#if JF12
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Enums;
#else
using Jellyfin.Data.Enums;
#endif
using MediaBrowser.Controller.Net;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Extensions.DependencyInjection;

namespace Jellyfin.Plugin.Tally.Api;

/// <summary>
/// Enforces the "Allow non-admin users" setting (<see cref="PluginConfiguration.AllowNonAdminUsers"/>) on the server:
/// when it is off, only administrators get channels, guide, scores and stream addresses. The web UI hides itself
/// for them too, but a setting that only the UI honors is no restriction.
/// </summary>
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Method)]
public sealed class TallyUsersAttribute : Attribute, IAsyncActionFilter
{
    /// <summary>The status a request is refused with, or null when it may go on.</summary>
    /// <param name="probe">The apps' capability probe (<c>/Client/v1/info</c>): refused with 404, which the apps take
    /// as "no Tally here" and hide the section quietly.</param>
    public static int? Refusal(bool allowNonAdmin, bool isAdmin, bool probe)
        => allowNonAdmin || isAdmin ? null : probe ? 404 : 403;

    public async Task OnActionExecutionAsync(ActionExecutingContext context, ActionExecutionDelegate next)
    {
        if (Plugin.Instance?.Configuration.AllowNonAdminUsers ?? true)
        {
            await next().ConfigureAwait(false);
            return;
        }

        var auth = await context.HttpContext.RequestServices.GetRequiredService<IAuthorizationContext>()
            .GetAuthorizationInfo(context.HttpContext.Request).ConfigureAwait(false);
        var probe = context.ActionDescriptor is ControllerActionDescriptor { ControllerName: "ClientApi", ActionName: nameof(ClientApiController.Info) };
        if (Refusal(false, auth.User?.HasPermission(PermissionKind.IsAdministrator) == true, probe) is { } status)
        {
            context.Result = new ObjectResult(new { error = "Tally is limited to administrators on this server" }) { StatusCode = status };
            return;
        }

        await next().ConfigureAwait(false);
    }
}
