window.onload = function() {
    reloadSubtitle();
    document.querySelector('.subtitle > span').onclick = reloadSubtitle;
    if (document.cookie.indexOf("blog_user=") >= 0) {
        document.body.classList.remove("anonymous");
    }
}

function reloadSubtitle() {
    var subtitles = [
        'Select purchase to purchase for $0.00 – PURCHASE / CANCEL', 
        'Are you sure you want to exist? — YES / NO',
        'Warning: No pixels were selected',
        'You need to be logged in to log out. Please log in to log out.'
    ];
    var subtitle = subtitles[Math.floor(Math.random() * subtitles.length)];
    var div = document.querySelector('.subtitle > span');
    div.innerHTML = subtitle;
}


var subtitles =
    ['Select purchase to purchase for $0.00 – PURCHASE / CANCEL', 
     'Are you sure you want to exist? — YES / NO',
     'Warning: No pixels were selected',
     'You need to be logged in to log out. Please log in to log out.',
     'Please, try again later',
     'You need to login to unsubscribe from spam',
     'Update Java Runtime?'],
    subtitle_el = document.querySelector('.subtitle > span');


function reload_subtitle() {
    do {
        var subtitle = subtitles[Math.floor(Math.random() * subtitles.length)];
    } while (subtitle === subtitle_el.innerText);
    subtitle_el.innerHTML = subtitle;
}


window.addEventListener("load", function() {
    subtitle_el.onclick = reload_subtitle;
    reload_subtitle();
    if (document.cookie.indexOf("grumpy_user=") >= 0) {
        document.body.classList.remove("anonymous");
    }
});
